# Design: Per-owner MicroVM warm pool with a persistent multi-task agent (Phase 3)

## Status: Design only. No code written. Builds on, and diverges from, Phase 1 in ways called out
## explicitly below — read "Where this forks from Phase 1" before assuming the two are compatible.

Two distinct capabilities, proposed together by the user and kept together here because they
reinforce each other, but each independently gated at implementation time:

1. **Per-owner, content-addressed MicroVM reuse.** Suspend a MicroVM instead of terminating it;
   tag it with the sha256 of the classpath it already has on disk, scoped to the owner that built
   it. A later build from the same owner with the same classpath resumes that MicroVM instead of
   launching fresh and re-downloading everything.
2. **A persistent, multi-task agent reachable over HTTP, streaming logs via SSE.** Instead of the
   Phase 1 agent's one-shot "fork a build, write `status.json`, done" model, the agent becomes a
   long-lived server that accepts build submissions over its exposed endpoint and streams that
   build's log output back over the same connection — one submission, one SSE response, same shape
   `BuildApi.streamLogs` already uses today for the Fargate path's log streaming.

## Where this forks from Phase 1 — read this before anything else

Phase 1 ([`microvm-build-backend-phase1.md`](microvm-build-backend-phase1.md)) made two decisions
that this document reverses, deliberately and for stated reasons — not by oversight:

| | Phase 1 | Phase 3 (this document) |
|---|---|---|
| Ingress connector | **None.** The control plane never calls into the MicroVM. | **Required.** The control plane (or the client, see "Who calls in" below) must reach the agent's HTTP endpoint directly. |
| Auth token | Never minted after launch. | Minted per call, used to authenticate every submission. |
| Agent lifetime | One build, then terminate. | Many builds, until idle/suspended/terminated by policy. |
| Build submission | Environment baked into `RunMicrovmRequest.runHookPayload()` at launch time. | Submitted later, over HTTP, after the MicroVM is already running (possibly resumed from suspend). |
| Status reporting | `status.json` written to S3, polled by the control plane. | SSE stream over the same HTTP connection the submission used — no polling. |

Phase 1's "no ingress connector" decision rested on a specific finding in
[`microvm-backend-integration.md`](microvm-backend-integration.md): the revised execution model
concluded the control plane should never call into the MicroVM, with the agent reporting
completion via a side channel instead. That finding is **correct for Phase 1's one-shot model** and
**inapplicable here**, because Phase 3's entire value proposition — submit multiple builds to one
warm agent without relaunching — requires exactly the inbound call Phase 1 avoided. This is not a
contradiction to resolve; it is two different lifecycles solving two different problems, and a
deployment chooses one lifecycle per MicroVM, not both at once for the same instance.

## Capability 1: per-owner content-addressed warm pool

### What AWS actually guarantees here — verified, not assumed

Checked against current AWS documentation rather than inferred from the SDK alone, because this
capability's entire cost case rests on exactly what "suspended" means:

- **Suspended MicroVMs incur snapshot storage charges, not compute charges** — confirmed directly
  (AWS: *"While suspended, you pay only snapshot storage charges"*). This is the capability's
  actual cost basis, matching what `microvm-build-backend.md` already established for the (different) PGO
  suspend/resume case.
- **Suspend/resume is per-instance, not content-addressed.** AWS's model is "suspend this specific
  `microvmId`, resume this specific `microvmId`." There is no AWS-native concept of "give me
  whichever suspended instance already has sha256 X on disk" — that matching logic is entirely
  this project's own, built on top of the generic primitive. Nothing in AWS's docs suggests
  otherwise, and this document does not pretend AWS does this work for us.
- **Account-level capacity is a pooled quota across `RUNNING` *and* `SUSPENDED` instances**
  together (confirmed: *"your account has a quota for the total memory that can be allocated
  across all your MicroVMs in the RUNNING or SUSPENDED state"*). A warm pool of suspended
  instances is not free capacity-wise — every suspended MicroVM counts against the same ceiling a
  running one does, vertically scaled up to 4x the quota. This directly bounds how large a
  per-owner warm pool can grow before it starts competing with that owner's (or another owner's,
  since the quota is account-wide, not per-owner) ability to launch new MicroVMs at all.
- **`maximumDurationInSeconds` bounds total lifespan including suspended time** (confirmed:
  *"including both running and suspended time... Range: 1–28,800 seconds (8 hours)"*). A warm
  MicroVM cannot be kept indefinitely regardless of how aggressively this project tries to — 8
  hours is a hard platform ceiling, not a policy choice. Any warm-pool design that assumes "keep it
  around until it's useful again" needs its own re-launch-and-rehydrate path for when that ceiling
  is hit, because AWS will terminate it out from under the pool regardless of our own idle policy.

### Why this is correctly scoped to "per-owner," not shared, and why that fixes the original
### concern

Isolation stays identical to what Fargate already has, and to what S3 CAS already does: a
suspended MicroVM belonging to Alice is never resumable by anyone whose builds don't resolve to
Alice's `ownerKey`. The content-addressing (the sha256 lookup) only ever searches *within* one
owner's own warm pool, exactly mirroring how `StagingService`'s existing per-owner CAS already
dedupes storage — this capability dedupes *compute environment* the same way, at the same
ownership boundary, not a new or looser one. There is no cross-tenant resume path to design
against here, because the lookup key is always `(ownerKey, sha256)`, never `sha256` alone.

### Data model

New DynamoDB table, `scaleout-microvm-pool` (a GSI on the existing `scaleout-builds` table was
considered and rejected — see "Why a separate table" below):

| Attribute | Notes |
|---|---|
| `ownerKey` (PK) | same normalized owner key `BuildRecord`/`StagingService` already use |
| `classpathSha256` (SK) | sha256 of the full dependency manifest (not individual jars — the *combination*, since that's what the resumed disk state actually represents) |
| `microvmId` | the suspended (or running) instance this entry points at |
| `state` | `WARMING` (just launched, not yet suspended) / `SUSPENDED` / `RESUMING` / `IN_USE` (currently running a build) / `STALE` (past its own freshness policy, pending termination) |
| `lastUsedAt` | updated on every resume, drives the staleness policy below |
| `createdAt` | for the 8-hour `maximumDurationInSeconds` ceiling — this entry becomes unusable once `createdAt + 8h` passes regardless of `state`, and must be proactively removed/relaunched rather than discovered stale on the next lookup attempt |
| `ttl` | DynamoDB TTL, set well past `createdAt + 8h` so a dead entry is garbage-collected automatically if nothing else cleans it up first |

**Why a separate table, not a GSI on `scaleout-builds`:** a warm pool entry outlives any single
build — it exists *between* builds, is looked up *before* a `BuildRecord` for the next build even
exists, and has a completely different access pattern (point lookup by `(ownerKey,
classpathSha256)`, never scanned by build state). Folding it into `scaleout-builds` would couple
two independently-evolving lifecycles into one table's schema for no shared benefit — the same
reasoning that already keeps build *state* (DynamoDB) and build *logs* (CloudWatch) in separate
stores today, stated explicitly in `scaleout-builder-control-plane.md`'s data model section.

### Lookup and reuse flow

```
BuildService.start() for an arm64 cell, MicroVM backend enabled:
  1. Compute classpathSha256 from the cell's resolved input manifest
     (the same digest-based manifest create() already builds for S3 CAS — reused, not recomputed
     from different inputs, so a cache hit here always means the actual classpath matches too).
  2. Look up (ownerKey, classpathSha256) in scaleout-microvm-pool.
  3. If found and state == SUSPENDED and createdAt + 8h has not passed:
       -> ResumeMicrovm, mark state = IN_USE, submit the build (see Capability 2 for how).
  4. If found but state == IN_USE (another build from the same owner is already running on it):
       -> fall through to launching fresh, exactly as if no entry existed. Do not wait or queue --
          see "Concurrency" below for why.
  5. If not found, or found but past its 8-hour ceiling:
       -> launch fresh (Phase 1's RunMicrovm path), and on successful completion, suspend rather
          than terminate, writing a new scaleout-microvm-pool entry.
```

### Concurrency: fall through, do not queue

If two builds from the same owner with the same `classpathSha256` arrive close together (e.g. CI
running two branches with identical dependencies), only one can resume a given `microvmId` — a
MicroVM is a single instance, not a pool unto itself. The second build falls through to launching
its own fresh MicroVM rather than waiting for the first to free up.

This is a deliberate choice, not a limitation accepted reluctantly: queuing would mean a build's
start time depends on an unrelated build's completion time, which is exactly the kind of
cross-build coupling `scaleout-builder-control-plane.md`'s own "Queueing" open item already
flags as undecided *for the simpler case* of the per-owner concurrency cap — introducing a second,
MicroVM-specific queue here, before that simpler case is even settled, compounds an open question
rather than resolving one. Falling through to a fresh launch costs the re-download Phase 1 already
pays for every build; it does not cost correctness or add a new failure mode.

### Staleness policy

A warm entry is swept (suspended MicroVM terminated, DynamoDB entry removed) when any of:

- `createdAt + 8h` has passed (AWS will have terminated it regardless; this is cleanup, not
  prevention).
- `lastUsedAt` is older than a configured freshness window (new config,
  `scaleout.microvm.warmPoolMaxIdleHours`, proposed default **not yet chosen** — needs a real
  usage pattern to size against, not a guess; see "Open questions").
- The owner's total warm-pool footprint (summed suspended-instance memory, mirroring the
  account-level quota's own accounting) exceeds a per-owner cap, oldest-`lastUsedAt`-first —
  needed because the account-wide `RUNNING + SUSPENDED` quota is shared, and one owner's warm pool
  growing unchecked could starve another owner's ability to launch anything, running or suspended.

This runs as an extension of the existing reaper (`BuildReaperHandler`), on the same
once-a-minute schedule, rather than a new scheduled job — the reaper already exists specifically
to garbage-collect resources a client stopped actively managing, and a stale warm-pool entry is the
same category of problem (something costing money that nothing is currently using), just for a
different resource.

## Capability 2: persistent multi-task agent with SSE log streaming

### What AWS actually guarantees here — verified, not assumed

- **The default inbound port is 8080, not 8443** (confirmed:
  *"By default, requests received at the endpoint are routed to port 8080 inside the MicroVM"*).
  The port can be changed via port routing configuration, but 8080 is the documented default this
  design should assume unless deliberately configured otherwise.
- **SSE is explicitly a supported inbound protocol** on the MicroVM endpoint, listed alongside
  HTTP/1.1, HTTP/2, WebSockets, and gRPC. This directly confirms the user's proposed shape — submit
  a build, get an SSE stream back on the same connection — is something the platform supports, not
  something this project would be working around.
- **Every request requires an auth token** in the `X-aws-proxy-auth` header, minted via
  `CreateMicrovmAuthToken`, **port-scoped** (a token can be scoped to one port, a range, or all
  ports) and capped at **60 minutes maximum lifetime** (confirmed directly from the API reference:
  *"expirationInMinutes... Maximum: 60 minutes"*).
- **Concurrent-connection and request-rate ceilings are fixed by vCPU size, not adjustable**
  (confirmed from the Lambda quotas page): at 4 vCPU — the size this project's own measured
  workload already requires (5.3–5.6 GB peak RSS, confirmed in `fargate-task-resource-usage.md`,
  carried over unchanged for the MicroVM case since the compile itself is identical work) — the
  ceiling is **32 concurrent connections and 40 requests per second**. "The agent can accept
  multiple tasks up to the point of vertical scaling" is correct as a claim, but the actual ceiling
  for our workload's instance size is 32 concurrent, not an open-ended number, and this document
  states that explicitly so nobody designs around an assumed-unlimited capacity later.
- **Not verified, and flagged honestly rather than assumed either way**: whether an auth token
  expiring mid-stream terminates an already-open SSE connection, or only gates new connection
  attempts. AWS's documentation describes token scoping and expiration at creation time but does
  not state this specific behavior anywhere found during research for this document. Given compiles
  can run 5–15 minutes and the token cap is 60 minutes, a single build's stream is very unlikely to
  hit this in practice — but a long-running agent accepting *many* sequential builds over its
  lifetime will mint many tokens over time, and this needs to be tested against the real platform
  before being relied on, not assumed from either direction.

### Who calls in: the control plane, not the client

The MicroVM's HTTPS endpoint is reachable from wherever the ingress connector permits — but the
*caller* in this design is still the control plane, submitting on behalf of a build it already
authorized via the existing `ownerKey` check, exactly as the control plane is the only thing that
calls `ecs:RunTask`/`DescribeTasks` today. The client (Maven plugin, CLI) never learns a MicroVM's
endpoint or holds an auth token — carried forward unchanged from Phase 1's own explicit non-goal,
*"Auth token forwarding to the client... the client never sees a MicroVM endpoint or token."* That
constraint does not loosen just because this design now calls into the MicroVM; it only changes
*who* on the control-plane side makes that call and when.

### Submission and streaming shape

```
POST https://<microvm-endpoint>/builds   (new agent-side route, port 8080)
  Headers: X-aws-proxy-auth: <token, minted by the control plane just before this call,
                              port-scoped to 8080, short expiry>
  Body: the same runHookPayload JSON shape Phase 1 already defined (build id, staging path,
        build kind, timeout, S3 bucket) -- submitted now over HTTP instead of baked into
        RunMicrovmRequest at launch time, since launch and submission are decoupled once the
        MicroVM can be resumed and reused.

  Response: Content-Type: text/event-stream
    data: {"cell":"NATIVE/ARM64","timestamp":...,"message":"..."}      (one per log line)
    data: {"cell":"NATIVE/ARM64","type":"cell-state","state":"SUCCEEDED"}
    data: {"type":"stream-end","reason":"build-terminal"}

  -- same event shape BuildApi.streamLogs() already defines for the Fargate path. The control
  plane relays this stream through to the client exactly as it already relays CloudWatch Logs
  today; from the client's perspective nothing about the wire contract changes.
```

### Why the agent must still be a real HTTP server, not Phase 1's fork-and-return hook

Phase 1's `/run` hook has to return within 60 seconds (the platform's own hook-timeout ceiling,
confirmed in `microvm-backend-integration.md`'s self-correction and reused unchanged in Phase 1's
own design), so it forks the compile and returns immediately — a build's actual duration is
invisible to that hook entirely. Capability 2's `/builds` route is **not** a lifecycle hook at
all — it is an ordinary application route the agent defines itself, with no AWS-imposed response
deadline, because the SSE response *is* the long-running operation; the connection stays open for
the build's full duration by design, the same way `GET /builds/{buildId}/logs` already stays open
for the Fargate path's whole build. The `/ready`/`/validate`/`/run` lifecycle hooks from Phase 1
still exist and still do their platform-defined jobs (snapshot timing, prefetch warming, initial
boot signal) — `/builds` is a fourth, independent route alongside them, not a replacement for any
of the three.

### Concurrency inside one agent

A single agent instance, while running, can have multiple `/builds` connections open
simultaneously — up to the 32-concurrent-connection ceiling at 4 vCPU noted above. Each connection
drives its own compile on its own thread (the same `runCompileAndWriteStatus` shape Phase 1's
`AgentHookServer` already sketches, generalized from "one fork per hook call" to "one fork per
`/builds` POST"), each writing its own `status.json` (or, since the SSE stream makes polling
unnecessary here, each simply ending its own SSE response when its build finishes — `status.json`
becomes optional telemetry rather than the control plane's only signal, since the response stream
itself already carries the terminal state event).

**This directly changes the resource-sizing question Phase 1 never had to ask**: Phase 1 sized one
MicroVM for one compile (4 vCPU, matching the measured single-build peak). A warm agent accepting
*multiple concurrent* compiles on the same instance needs to size for N concurrent compiles' combined
peak, not one — and `native-image` is documented elsewhere in this project's own cost analysis as
saturating whatever CPU it's given regardless of project size. Two concurrent compiles sharing 4
vCPU does not do 4 vCPU of work twice as fast; it does roughly half the work each, roughly twice as
slow, which may be an acceptable trade for a developer-facing concurrency ceiling but is a real
behavior change from Phase 1's "one build, full 4 vCPU, as fast as the hardware allows" model. This
needs to be an explicit, stated policy decision (how many concurrent builds does one agent accept
before refusing a submission and letting the control plane fall through to launching a fresh
MicroVM instead), not something discovered by load.

## Combined flow: both capabilities together

```
BuildService.start() for an arm64 cell:
  1. Look up warm pool entry (Capability 1).
  2a. Found, resumable: ResumeMicrovm. Resume hook refreshes credentials/connections
      (the same hazards microvm-build-backend.md already identified for the PGO case apply
      identically here -- expired task-role credentials, seeded-randomness reuse, dead CloudWatch/
      S3 connections -- carried forward unchanged, not re-derived).
      Submit the build via POST /builds (Capability 2), stream the SSE response through to the
      client.
  2b. Not found, or not resumable: RunMicrovm fresh (Phase 1's launch path, unchanged).
      Submit the build via POST /builds the same way -- the agent's /builds route is identical
      whether the MicroVM is freshly launched or just resumed; the agent itself has no notion of
      "first build" vs. "nth build" on this instance.
  3. On build completion (successful or failed), the control plane does NOT terminate the
     MicroVM. It issues SuspendMicrovm instead, and upserts the scaleout-microvm-pool entry
     with the cell's classpathSha256, state = SUSPENDED, lastUsedAt = now.
  4. The staleness sweep (extended reaper) eventually terminates it, per the policy above.
```

## What this means for `BuildRecord.CellRecord` and `CellHandle`

Phase 1 already widens `CellRecord` with `backend`/`microvmId`/`microvmEndpoint` fields (see that
document's "widening `taskArn` without a flag day" section). This document needs one further
addition: `CellRecord` (or the warm-pool table, not necessarily both) must also carry whether this
particular cell *resumed* an existing MicroVM versus launched fresh, purely for observability — a
rising "fresh launch despite a warm-pool hit existing" rate would mean capability 1's lookup logic
is failing silently, and that needs to be visible in metrics rather than only inferable from warm-
pool table state after the fact.

`CellHandle.MicroVmHandle` itself needs no change — `microvmId` and `endpoint` are already
sufficient to resume, submit, and suspend; nothing about the handle shape differs between a fresh
launch and a resumed instance from the caller's side.

## Security considerations specific to this document, beyond what Phase 1 already covers

- **The ingress connector this document requires is new attack surface Phase 1 deliberately had
  none of.** Phase 1's security posture rested partly on "the control plane never calls into the
  MicroVM, so there is nothing on that side to secure." This document reverses that, so the ingress
  connector's own configuration (which network it's reachable from, whether it's open to the public
  internet or VPC-scoped) becomes a real decision this document must not leave implicit. Given the
  control plane is the only caller and nothing about this design requires public reachability,
  the connector should be VPC-scoped, matching the egress connector's own existing VPC-scoped
  design in Phase 1 — not stated as settled here, but flagged as the default this document assumes
  pending explicit confirmation.
- **Auth tokens must never be logged.** `X-aws-proxy-auth` carries a bearer credential good for up
  to 60 minutes against a specific MicroVM; this project's existing structured-logging practice
  (every control-plane log line already carries `buildId`/`ownerArn`, per
  `scaleout-builder-control-plane.md`'s observability section) must explicitly exclude this header
  value from any log line that touches it, the same discipline already applied to AWS credentials
  elsewhere in this codebase.
- **A resumed MicroVM's disk still has the *previous* build's inputs on it.** This is the entire
  point of Capability 1 when the classpath matches, but it means a bug in the sha256 computation
  (hashing the wrong manifest, or hashing a manifest that omits something that actually changed)
  silently serves stale dependency jars to a build that thinks it has fresh ones. This is a
  correctness risk, not just a performance one, and argues for the sha256 lookup key being
  computed from the *exact* same code path `StagingService`'s own digest-based manifest already
  uses for S3 CAS — reusing that function directly rather than writing a second, parallel digest
  computation that could drift from it over time.

## Open questions

1. **Whether token expiry kills an in-flight SSE connection** — flagged above as researched and not
   found either way. Needs a real test against the platform before this design can claim the
   60-minute token ceiling is a non-issue for streaming.
2. **`scaleout.microvm.warmPoolMaxIdleHours` default value** — needs real build-frequency data from
   actual usage (how often does the same owner rebuild the same classpath, and how soon after the
   previous build) to size sensibly. A guess here risks either churning warm instances uselessly
   (too short) or paying needless snapshot storage for pools nobody returns to (too long).
3. **Per-owner warm-pool memory cap**, mentioned in the staleness policy, has no proposed default
   either, for the same reason — needs usage data, not a guess.
4. **Whether `/builds`' concurrent-compile-per-agent ceiling should be a hard refusal (503, control
   plane falls through to a fresh launch) or a soft queue inside the agent itself** — leaning
   toward hard refusal, matching Capability 1's own "fall through, do not queue" concurrency
   decision for consistency, but not settled here.
5. **Ingress connector scope** (VPC vs. public) — assumed VPC-scoped above, not confirmed against
   an actual target deployment's network layout, the same open item Phase 1 already carries for its
   egress connector.

## Relationship to Phase 1 and Phase 2

This document is **Phase 3**, independently gated from both. Phase 1 (no suspend/resume, one build
per MicroVM, no inbound connector) can ship and run in production without any of this document's
capabilities ever being built. Phase 2 (PGO suspend/resume, one build's own instrument→profile→
optimize phases) is also independent of this document — a MicroVM suspended between its own PGO
phases is not in the warm pool this document defines, and does not accept submissions from other
builds. All three phases can coexist as different configured behaviors for different cells, but
none is a prerequisite for either of the others, and this document does not assume Phase 2 has
been built before Phase 3 is.

## Related documents

- [`microvm-build-backend-phase1.md`](microvm-build-backend-phase1.md) — the one-shot model this
  document forks from, and the `CellLauncher`/`CellHandle` abstractions this document extends
  rather than replaces.
- [`microvm-build-backend.md`](microvm-build-backend.md) — Phase 2's PGO suspend/resume case and
  its resume-hazard analysis (expired credentials, seeded randomness, dead connections), reused
  unchanged by this document's own resume flow.
- [`storage-layout-and-isolation.md`](storage-layout-and-isolation.md) — the per-owner CAS model
  this document's warm-pool isolation boundary mirrors exactly.
