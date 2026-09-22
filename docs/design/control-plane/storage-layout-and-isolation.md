# Storage layout and tenant isolation

## Status: Agreed — to be implemented in migration step 4

Companion documents: [`scaleout-builder-control-plane.md`](scaleout-builder-control-plane.md) for the
service design, [`migration-from-direct-ecs-access.md`](migration-from-direct-ecs-access.md) for how
clients get here.

## The tension this document resolves

Content-addressed storage works **because** it is shared. A blob is stored once under its own digest
and reused by everyone who needs it. Every isolation boundary added to it removes dedup across that
boundary. The two goals are in direct opposition, so the layout has to state which one it is buying
and at what cost.

The short answer: **isolation wins, because dedup is much cheaper to give up than it first appears.**

## What dedup is actually worth

Its value is almost entirely **first-build latency**, not storage cost.

A realistic dependency set is 50–200 MB. At S3 standard rates a duplicated 200 MB copy costs well
under a cent a month, so five engineers each holding their own copy of `commons-lang3` (709 KB) have
collectively spent 3.5 MB. That is not a number worth designing around.

What dedup genuinely saves is re-uploading unchanged jars on **every build by the same engineer**, and
that survives any per-engineer boundary. Measured over three end-to-end runs against the shared
bucket: `commons-lang3` had exactly **one** content-addressed entry, while the project's own 5060-byte
jar had **one per run** — Maven's jar embeds timestamps, so it is not byte-reproducible. That is the
real steady state, and it is entirely within one engineer's own history.

So per-engineer namespacing costs each engineer one upload of their dependency set, once, which they
would pay on their first build regardless.

## Two problems with a globally shared store

Both are well known from Docker registries and from Bazel and Nix remote caches. Neither is exotic.

### 1. Existence is an oracle

`POST /builds` answers with the digests the server does **not** already hold. Under a shared store,
telling engineer A that digest X is already staged reveals that somebody uploaded content hashing to
X. For a public dependency jar that is harmless. For a proprietary artefact it is an existence check
against another team's inputs, obtainable by anyone who can guess or otherwise obtain the hash.

### 2. A manifest is a claim, not a proof

This is the serious one. The input manifest is a list of digests the client *says* it needs. Under a
shared store, A can submit a manifest naming digest X without ever having possessed X, and the service
will `copyObject` that blob out of the shared store into A's build prefix. The build then compiles
against content A was never given.

There is no upload step to catch it: the whole point of the negotiation is that already-present digests
are **not** re-uploaded, so "A never uploaded X" is indistinguishable from "X was already there". It is
a narrow cross-tenant read primitive, but it is a real one.

The standard mitigations are proof-of-possession (track which owner uploaded which digest and refuse to
materialise blobs the requester never supplied) or namespacing (make the store per-owner so there is
nothing foreign to claim). Namespacing is chosen: it removes both problems structurally rather than by
remembering to check, and given the cost analysis above it is nearly free.

## The layout

```
s3://scaleout-build-staging-<account>-<region>/
└── workspace/
    └── <ownerHash>/                              32 hex chars of SHA-256(ownerKey)
        ├── cas/
        │   └── <blobSha256>                      one object per distinct input blob
        └── builds/
            └── <buildId>/
                └── <buildKind>/
                    └── <architecture>/
                        ├── native-image.args     client-generated, an ordinary input
                        ├── <project>.jar
                        ├── lib/
                        │   └── <dependency>.jar  server-side copies out of cas/
                        └── output/
                            └── <imageName>       the produced native binary
```

`StagingLayout` already takes `buildsPrefix` and `casPrefix` as constructor arguments, so this is a
construction-time change — `new StagingLayout("workspace/" + ownerHash + "/builds", "workspace/" +
ownerHash + "/cas")` — not a redesign of the layout class.

### Correction: the CAS level must be keyed by blob digest, not by project digest

The shape originally proposed was `workspace/sha256/<sha256_of_uploaded_project>/`. Keying the
content-addressed level by a **project** digest does not work, and the reason is worth stating because
it is the whole mechanism:

A project digest changes whenever **any** file in the project changes. Since the project's own jar is
not byte-reproducible between builds, that digest changes on *every single build*. Keying the store by
it would mean every build writes to a fresh namespace and re-uploads the entire dependency set — which
is precisely the cost dedup exists to avoid. The measured result above (one `commons-lang3` entry
across three runs) would become three entries, then thirty, then three hundred.

Content addressing only composes if each object is keyed by **its own** content. So:

- `cas/<blobSha256>` — the digest of *that blob*. `commons-lang3-3.19.0.jar` has one key forever.
- `builds/<buildId>/…` — per-build working area, keyed by build identity, which is where
  per-build separation belongs.

Two levels, two different keys, two different jobs. A project dimension can legitimately be added to
the **builds** side later (e.g. `builds/<projectId>/<buildId>/`) for grouping and retention, using a
non-sensitive identity like `groupId:artifactId`. It must not be added to the `cas` side.

### Why `ownerHash` is a hash

`ownerKey` is an ARN and may contain `/` and `@` — `arn:aws:iam::123:role/Dev/alice@corp.com` for an
SSO caller. Embedding it raw would inject path separators into S3 keys, and part of it (the session
name) is influenced by the caller. Hashing gives fixed-length, opaque, separator-free path segments
that a caller cannot steer.

SHA-256 truncated to 32 hex characters — 128 bits, so collisions are not a practical concern, and keys
stay readable. The mapping is not a secret; it is recorded on each build record as `ownerKey` so an
operator can resolve a prefix back to a principal.

## What this does and does not isolate

| Property | Provided |
|---|---|
| A cannot read B's build inputs | Yes — nothing of B's is in A's namespace to claim |
| A cannot learn what B has uploaded | Yes — the existence oracle is scoped to A's own store |
| A cannot read B's build outputs | Yes — enforced by the `ownerKey` check, independently of layout |
| A cannot see that B has builds at all | Yes — `findOwned` returns empty, and 404 is indistinguishable from "not yours" |
| A cannot interfere with B's **running build process** | **No** — see below |

The last row is the honest limit. Tasks share one ECS cluster, so isolation between running builds is
whatever Fargate provides at task level. That is adequate for engineers inside one team who could
already run code in the account; it is *not* adequate for untrusted tenants. Crossing that threshold
means VM-level isolation, which is the MicroVM backend, not a storage layout.

**Authorization remains the `ownerKey` comparison, not the key prefix.** The layout makes cross-tenant
access impossible to *express*; the check makes it impossible to *perform*. Relying on prefixes alone
would be one service bug away from a boundary violation, and S3 prefixes are not an access-control
mechanism unless enforced by policy — which, since every client access is through a presigned URL
minted by the service, they are not here.

## Retention

Per-owner namespacing makes lifecycle rules tractable, because each prefix has one owner:

- `workspace/*/builds/` — expire after 30 days. Build working areas are reconstructible from `cas/`
  plus the manifest recorded on the build record.
- `workspace/*/cas/` — no expiry by default. Expiring a blob silently converts a future "already
  staged" into a re-upload, which is correct but slow; if storage ever justifies it, expire on
  *last access* rather than creation, since a two-year-old `commons-lang3` is still the current one.

The DynamoDB record's own `ttl` is 14 days, which is deliberately shorter than the build prefix
lifecycle: the metadata needed to *interpret* a prefix should not outlive it by much, and an orphaned
prefix with no record is easier to reason about than a record pointing at deleted objects.

## Multi-account: not now

Considered and rejected for this iteration.

Cross-account dedup needs either a shared bucket with a cross-account policy — which reopens every
question above, now across a trust boundary rather than within a team — or S3 replication, which
duplicates the storage anyway, so the cost is paid *and* the complexity is kept.

The per-account costs are not small either: N control-plane deployments, N ECR repositories holding N
copies of the agent image to keep in step, N sets of Fargate quotas, N endpoints for developers to
configure, and N stacks to upgrade in lockstep whenever the wire contract changes.

Per-owner namespacing inside one account already delivers the isolation properties that were actually
being asked for. The case that genuinely justifies separate accounts is untrusted tenants — and that is
the same threshold at which Fargate task isolation stops being sufficient, so it is a conversation
about the execution backend, not about buckets.

## Consequences for the existing deployment

The bucket currently in use has objects under the old flat `cas/` and `builds/` prefixes, written by
the direct-ECS path during end-to-end testing. They are not migrated: the content is regenerable, the
direct path is being deleted rather than kept, and rewriting keys to preserve a dedup cache that will
be repopulated on the next build is effort spent for nothing. A lifecycle rule expiring the legacy
`cas/` and `builds/` prefixes is enough.

## Open items

- Whether to add a project dimension to the builds side (`builds/<projectId>/<buildId>/`) for
  retention grouping. Useful, not required, and must not touch `cas/`.
- Whether `missingDigests` should additionally require proof-of-possession *within* an owner's
  namespace. Unnecessary for the threats above — A claiming A's own blob is not an escalation — but it
  would matter if a namespace were ever shared by more than one principal, for example a team-wide
  CI role.
- Whether per-owner CAS should be measured before it is assumed cheap. The estimate above uses a
  200 MB dependency set; a project with a genuinely large classpath across many engineers deserves a
  real number rather than an inference.
