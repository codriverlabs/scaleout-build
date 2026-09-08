# Staging: What's Implemented, What Was Considered, and Why

This document covers the *staging* axis — how build inputs move from the plugin to a remote
builder and how artifacts move back — separately from `docs/PURE_ECS_ALTERNATIVE.md`'s compute/
orchestration axis (Step Functions vs. pure ECS, and which ECS launch type). The two are
orthogonal: nothing here depends on which launch type is used, and nothing in the launch-type
document depends on which staging mechanism is used.

## 1. What's actually implemented today

**Staged content is exactly the resolved runtime classpath, never the full local Maven cache.**
`NativeImageInputPlanner.planDerived()` builds its file list from
`ProjectInputs.runtimeClasspath()`, which `BuildMojo.resolveRuntimeClasspath()` populates from
`project.getRuntimeClasspathElements()` — Maven's own already-resolved, already-deduplicated
runtime-scope classpath for the specific module being built. That excludes build-plugin jars
(compiler, surefire, `native-image-maven-plugin` itself), test-scope and unused provided-scope
dependencies, and every unrelated jar from other projects sitting in `~/.m2/repository` — which on
a real dev machine is routinely multiple GB, versus a classpath that's a small, precise subset of
it. It does have to include the *whole* runtime dependency closure, not a hand-picked slice of it —
`native-image`'s closed-world static analysis walks the full reachable class graph from the entry
point, and omitting a transitively-used jar produces build failures or runtime
`ClassNotFoundException`s, not a smaller binary.

**One real gap, not yet closed:** when Quarkus has already generated its own native-sources
directory and argfile, `NativeImageInputPlanner.planFromNativeSources()` stages **the entire
generated sources directory as-is**, unfiltered — trusting Quarkus's own output rather than
re-deriving the classpath the way `planDerived()` does. This path doesn't get the same
"only the necessary jars" guarantee. Worth checking whether that directory ever contains more than
the derived-mode path would for any given project.

**Deduplication is content-hash-based, whole-file, via server-side `CopyObject` — not a diff/delta
mechanism.** `S3StagingSink.stageThroughContentAddressedStore()`:

1. SHA-256-hashes each classpath jar locally.
2. Attempts a conditional `PutObject` to `cas/{sha256}` with `If-None-Match: *` (S3's own
   conditional-write support, GA since August 2024 — confirmed against AWS's own
   conditional-writes documentation, not assumed). If that exact content exists anywhere in the
   bucket already (from *any* prior build, any project, any architecture), S3 rejects the write
   with `412 Precondition Failed` and **no upload happens** — zero bytes transferred; otherwise
   the upload succeeds and this call becomes the one that populated that key.
3. Either way, a server-side `CopyObject` materializes that blob into the build's own
   `builds/{buildId}/{kind}/{arch}/lib/` key — free, no data movement, S3-internal reference only.

**This is deliberately not a check-then-act `HeadObject`-then-`PutObject` pair**, which would have
a real race window under concurrency: two callers racing to stage the very same not-yet-uploaded
content could both observe "not present yet" and both perform a full upload — S3 accepting both
writes is harmless (both write identical bytes to the same content-addressed key), but it silently
defeats the dedup this store exists for. A single conditional `PutObject` closes that window
atomically, and — unlike an in-process lock — cross-process too: S3 guarantees "the first write
operation to finish succeeds… [and] fails subsequent writes with a 412 Precondition Failed
response" for the same key, so at most one caller ever actually uploads, no matter how many
processes or JVMs are racing. Verified for real, not just reasoned about: `S3StagingIntegrationTest`
runs four concurrent, `CyclicBarrier`-synchronized "cells" (mirroring a realistic single
invocation's matrix — `NATIVE`/`NATIVE_PGO_INSTRUMENT`/`NATIVE_PGO_OPTIMIZE` for one architecture
plus `NATIVE` for a second) all racing to stage the same shared classpath jars for the first time,
against SeaweedFS's real S3 gateway, and asserts the aggregate bytes transferred across all four
equals exactly one copy of the shared content plus each cell's own unique argfile — not more.

The generated `native-image.args` file is the one thing *not* deduplicated — uploaded fresh, in
full, every build. It's small (a few KB at most), so this is a deliberate simplification.

Every build gets a fresh `UUID.randomUUID()` (UUIDv4) as its build ID — there is no persistent
identity across builds the way a git commit SHA would be; only the `cas/` prefix persists.

## 2. Alternatives considered, and why they weren't adopted

### S3 Files

Real, verified metering mechanics (from AWS's own "How S3 Files is metered" documentation, not
inferred): storage is charged only for the fraction of data resident on a "high-performance
storage" tier (files ≤128 KiB by default, expiring after 30 days of no access by default); reads
of files above that threshold, or any read ≥1 MiB, stream directly from the bucket at plain S3 GET
rates with **no S3-Files-specific charge**. This sounds like it should behave similarly to the
current CAS dedup for large files — and for *reads*, it roughly does.

Two structural problems make it a worse fit than the current design, though:

1. **No cross-object deduplication at all.** S3 Files meters and caches per file (per S3 key), with
   no concept of "this file's bytes are identical to that other file." If the current per-build
   materialized-copy layout stayed the same (a fresh key per build), S3 Files would re-import and
   re-meter identical small jars on every build that touches them — the exact thing the current
   `cas/` + `CopyObject` mechanism exists to avoid.
2. **Writes don't get the large-file bypass reads do.** Writes always land on the high-performance
   tier first (at $0.06/GB), then incur an export-sync charge back to the bucket (~$0.03/GB) — there
   is no "large writes stream straight through" exception the way there is for reads. For a
   workload whose output artifacts are large native binaries, this is the wrong side of the
   asymmetry to be on.

See §0 of `docs/PURE_ECS_ALTERNATIVE.md` for the separate, compute-axis reason S3 Files matters at
all in this project: it's the staging mechanism used for the `FARGATE`/`MANAGED_INSTANCES` launch
types specifically because S3 Files (unlike raw EC2) supports it — that's a launch-type
capability-matrix decision, independent of whether it's the *best* mechanism for input/output
transfer in the abstract.

### EFS

Mature, real NFS, strongly consistent, POSIX-compliant — the safe, boring choice if a workload
genuinely needs a live, shared filesystem view. It has no built-in content-based deduplication
either (a real EFS file at a given path is just a file; two identical files at two different paths
are two separate files, same as S3 Files). Flat per-GB storage pricing (no free-large-file-read
exception, no per-operation access-tier metering) makes its cost easier to reason about than S3
Files, but doesn't solve the dedup problem this workload actually has.

### Mountpoint for Amazon S3 (client-side, for the plugin's own staging)

Ruled out early for this specific job: Mountpoint's write model has no server-side copy operation,
so the CAS dedup mechanism (`HeadObject` + `CopyObject`) can't be implemented through it — using
Mountpoint here would mean re-uploading full bytes for every materialized copy, discarding the
dedup benefit entirely. (This is a different question from whether Mountpoint fits the *agent's*
read-mostly, write-once pattern inside an EC2-launch-type container — it does, for that narrower
use case; see `docs/PURE_ECS_ALTERNATIVE.md` §0.)

### `git archive` (and git as a transport generally)

`git archive --format=tar HEAD | zstd` is a genuinely better whole-project snapshot mechanism than
a naive `tar` of the working directory — it only includes tracked files, automatically respecting
`.gitignore`, with zero extra include/exclude logic, and ties the snapshot to a natural, stable
identity (the commit/tree SHA) instead of a random UUID.

It represents a fundamentally different *identity and dedup axis* than what's implemented:
commit-SHA-level snapshot identity, versus the current per-file content-hash identity. The two
aren't directly comparable without knowing a project's actual mix of source-vs-dependency bytes,
and adopting it wholesale would give up the current jar-level cross-module, cross-build dedup
unless combined with it.

One real correctness gap, not a minor detail: **`git archive` only archives committed content.**
Running against a dirty working tree — the normal state during active development — would silently
ship stale code. `git stash create` (with `--include-untracked` to also catch new, not-yet-`git
add`ed files) can capture a snapshot of the dirty state as an archivable commit object without
touching the actual working tree/index/HEAD, but this adds a real code path, not a trivial one-line
swap.

**Not implemented as a replacement for the current CAS mechanism** — noted here as a considered
alternative with a real, distinct tradeoff, not adopted.

### EBS io2 + rsync

Ruled out by a hard, documented AWS constraint, not a design preference: **"You can attach at most
one Amazon EBS volume to each ECS task, and it must be a *new* volume. You can't attach an existing
Amazon EBS volume to a task."** Every task gets a fresh, empty volume — rsync's delta-transfer
benefit requires a *persistent* destination to diff against, which this constraint makes
impossible for the ephemeral-task-per-build compute model this project uses. io1/io2 Multi-Attach
doesn't help either — it shares raw blocks across up to 16 same-AZ instances, explicitly not a
shared POSIX filesystem view ("standard file systems... are not designed to be accessed
simultaneously by multiple servers" — AWS's own EBS Multi-Attach documentation), and doesn't apply
to Fargate at all.

Making rsync's benefit real would require abandoning ephemeral per-build Fargate tasks for a pool
of long-lived worker instances with durable local/attached storage — a legitimate but genuinely
different compute architecture, not a storage-layer swap. `gp3`, not `io2`, would be the sane
choice if that path were ever taken; `io2`'s extra cost buys provisioned-IOPS headroom for
latency-sensitive random I/O (databases), not occasional source-tree syncing.

### Lambda as a runner (tangential, compute-axis, noted here because S3 Files came up in this context)

Confirmed real and documented: AWS's own `CfnFileSystem` CDK example shows
`lambda.FileSystem.fromS3FilesAccessPoint(...)` — S3 Files does mount into Lambda, via the same
VPC + access-point + mount-target mechanism Lambda already uses for EFS. The storage-mounting side
works. Lambda itself is a poor fit for this workload regardless: a 15-minute hard execution ceiling
(native-image builds routinely exceed that for non-trivial projects) and a 10 GB / 6 vCPU ceiling
(below this project's own existing Fargate defaults of 16 GiB / 4 vCPU). This is a compute-model
conclusion, not a staging one — see `docs/PURE_ECS_ALTERNATIVE.md` for why Fargate Spot fits this
workload's restart-tolerant, stateless build shape well, including the concrete finding that
**AWS CodeBuild has no Spot-equivalent discount at all** (only full-price on-demand fleets, or
continuously-billed reserved-capacity fleets) — a real point in Fargate Spot's favor for a workload
this tolerant of interruption.

## 3. Worked example: KubeMicroVM (real measured numbers, not estimates)

KubeMicroVM (`~/projects/microvm/KubeMicroVM`) has **two** native-image modules today —
`operator-controller` and `operator-cli` — not three; every `pom.xml` in the project was checked
for an active native profile with a native-image builder. That's 4 parallel builds (2 modules × 2
architectures), not 6.

| | `operator-controller` | `operator-cli` |
|---|---|---|
| Dependency closure (`project.getRuntimeClasspathElements()` output) | 54.0 MiB (238 jars) | 69.3 MiB (170 jars) |
| ...of which ≤128 KiB | 4.66 MiB (137 files) | 3.29 MiB (84 files) |
| ...of which >128 KiB | 46.86 MiB (80 files) | 62.80 MiB (64 files) |
| Native binary output | 133.1 MB | 115.0 MB |

145 jars are byte-identical by filename across both modules' dependency closures (43.4 MiB) — real
cross-module overlap, measured with `comm -12` against the actual built `target/quarkus-app/lib/
main/` directories, not assumed.

**Plain S3 (current CAS design), one full round of 4 builds:**

- Unique bytes ever uploaded = union of both closures = 54.0 + 69.3 − 43.4 ≈ **77.8 MiB**, uploaded
  exactly once, regardless of being needed by all 4 builds (jar content doesn't vary by
  architecture at all).
- Everything else — the other 3 builds' worth of otherwise-identical jars — is a server-side
  `CopyObject`: $0 data transfer, request-only cost (≈900 copy calls across 4 builds ≈ $0.0045).
- Output uploads: 4 binaries × ~120 MB avg ≈ 480 MB PUT.
- Storage while retained: ≈78 MiB (CAS) + 246 MiB (per-build materialized copies) + 480 MB
  (outputs) ≈ 780 MB × $0.023/GB-month ≈ **$0.018/month**.
- **Total for one round: well under $0.05.** A rebuild with unchanged dependencies (only source
  changed) costs even less — no new CAS uploads needed at all.

**S3 Files, same scenario:**

- 91% of both modules' dependency bytes (46.86 + 62.80 MiB) are >128 KiB and stream free of
  S3-Files-specific *read* charges — comparable to plain S3 for that majority slice.
- The small-file tier (≤128 KiB, ~8 MiB combined) would be re-imported and re-metered up to 4 times
  over for identical content, since S3 Files has no cross-object dedup — small in absolute dollars
  here (~$0.001), but structurally the exact failure mode described in §2.
- Writing the 4 output binaries (480 MB combined) through an S3-Files mount would cost
  ≈480 MB × ($0.06 write + $0.03 export-read)/GB ≈ **$0.043** for that step alone — versus a plain
  `PutObject`, whose cost doesn't scale with file size. (Not a live cost today: output retrieval
  already goes through direct `S3ArtifactRetriever`/`GetObject`, bypassing any mount entirely,
  regardless of which mechanism handles input staging.)

**Conclusion for this project's actual shape:** neither option is expensive in absolute terms at
this scale — cents per build round either way. The real differentiator is mechanism correctness:
plain S3's CAS dedup pays once for identical content regardless of build/architecture count; S3
Files pays per-file, per-build, with no cross-object awareness, and specifically penalizes
large-artifact writes. For a project whose outputs are 100+ MB native binaries, that's a structural
reason to keep the current plain-S3 CAS design. Scaling up (more modules, more architectures)
should widen this gap, not narrow it — plain S3's dedup benefit compounds with more builds sharing
the same `cas/` content, while S3 Files' per-build small-file and output-write penalties scale
roughly linearly with build count.

## 4. Recommendation

Keep the current plain-S3, content-hash CAS design as the staging mechanism for input
transfer. It is already implemented, already correctly scoped to the resolved runtime classpath
(not the full `.m2` cache), and demonstrably cheaper and structurally better-suited than S3 Files
for this workload's actual shape (jar-heavy dependency closures with real cross-module overlap,
large native-binary outputs).

Real, not-yet-acted-on follow-ups from this analysis:

- Close the native-sources-path gap (§1) — `planFromNativeSources()` should ideally get the same
  scrutiny `planDerived()` gives its file list, rather than trusting Quarkus's directory as-is.
- No lifecycle/expiration policy exists for old `builds/{buildId}/...` prefixes or the `cas/` store
  — storage grows unboundedly over time without one. Not urgent at current measured scale (cents/
  month), but worth adding before this sees sustained real usage.
- If `git archive`-based snapshotting is ever wanted (e.g., for build traceability via commit SHA),
  it would need to be layered *alongside* the existing jar-level CAS dedup, not as a replacement,
  to avoid regressing the dedup benefit demonstrated in §3.
