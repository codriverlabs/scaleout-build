# Design: Lambda MicroVM build backend

**Status: design only. No code written, nothing run on the platform.**

A second build backend alongside Fargate, using Lambda MicroVMs for `arm64` cells, motivated by
suspend/resume rather than by capacity. Platform limits and the client/worker split are in
[`microvm-platform-fit.md`](microvm-platform-fit.md); measured requirements in
[`fargate-task-resource-usage.md`](fargate-task-resource-usage.md).

## Why, in one paragraph

Profile-guided optimisation is two phases with a profiling run between them, and today those are unrelated
builds — `BuildKind` says collecting the `.iprof` "happens entirely outside this tool". A worker that can
suspend between phases keeps its classpath and page cache, and can run the profiling workload in the same
environment that produced the instrumented binary and will produce the optimised one. That last point is
about *profile accuracy*, not speed, and it is the strongest argument here.

## Resource fit is exact, which is worth noticing

Lambda MicroVMs bill baseline-plus-consumption, with CPU allocated at a **2:1 memory-to-CPU ratio** and
peak vertical scaling to **4× the configured baseline**
([launch blog](https://aws.amazon.com/blogs/compute/announcing-lambda-microvms-serverless-compute-environments-with-vm-level-isolation-and-near-instant-startup/)).

| Baseline | Peak |
|---|---|
| 2 GB / 1 vCPU | **8 GB / 4 vCPU** |

Our measured need is 5.3–5.6 GB peak RSS and full use of 4 vCPU. A 2 GB baseline gives exactly the 8 GB /
4 vCPU peak the build wants, and *"resource usage above the baseline is only billed during active use"* —
which suits a workload that is idle-then-bursty by nature. On Fargate we pay 4 vCPU / 8 GiB for the whole
task including the S3 download and upload phases.

## Cost model, and where my first estimate was wrong

Verified from [Lambda pricing](https://aws.amazon.com/lambda/pricing/) and
[Running and using MicroVMs](https://docs.aws.amazon.com/lambda/latest/dg/microvms-launching.html):

- Running: compute charges.
- **Suspended: snapshot storage only, no compute.**
- Terminated: nothing.
- Additionally: snapshot storage, **data written on suspend**, and **data read on resume**.

That last line undercuts the byte-savings argument I first reached for. Suspending a MicroVM holding
several GB of memory state writes a snapshot of that size; re-downloading 54 MB of classpath from S3 is
trivial by comparison. **Suspend/resume is not justified by avoiding the S3 download.**

What it is justified by:

1. **Profile accuracy** — the profiling run happens on the same hardware and image as both compiles.
2. **Eliminating provisioning and image pull for phase two** — measured at ~20 s and ~8 s on arm64.
3. **No compute charge across the profiling run**, which may be minutes or hours of the user's workload.

Point 3 is the real economic case, and it is about the *gap between phases*, not the phases themselves.

## Lifecycle and state machine

Today's worker lifecycle is stateless and terminal:

```
RunTask → poll DescribeTasks → container exits → task gone
```

A MicroVM's is not:

```
RunMicrovm → (endpoint + auth token) → RUNNING
   ↓ SuspendMicrovm                      ↑ ResumeMicrovm
SUSPENDED ────────────────────────────────┘
   ↓ TerminateMicrovm
gone
```

`CellState` today is `PENDING, PROVISIONING, RUNNING, SUCCEEDED, FAILED, CANCELLED` — every value assumes a
cell runs once to a terminal state. A suspended cell is none of those: it has not failed, has not
succeeded, and is not running.

**Adding `SUSPENDED` is a wire-contract change**, and it breaks old clients in an unobvious way: Jackson
deserialising an unknown enum value fails, so a client built before the change would error on a status poll
rather than degrade. Options, in order of preference:

1. **Keep `SUSPENDED` off the wire.** Model it server-side only and report `RUNNING` to clients, since from
   the client's perspective the cell is still in progress. Costs nothing in compatibility; loses the
   ability to show a user why nothing is happening.
2. Add it and require a client version bump, using the existing `clientVersion` field to detect and warn.
3. Add it with `@JsonEnumDefaultValue` on a catch-all so old clients coerce it to something benign.

Option 1 is probably right for a first implementation: the state is an implementation detail of one
backend, and the contract should not grow a value that only one backend can produce.

## Who runs the profiling workload

The design decision that actually matters, because it changes what the plugin is responsible for.

Today PGO's middle step is explicitly out of scope. Suspend/resume only pays off if the profiling run
happens *inside* the worker, which means the plugin must learn how to exercise the binary — a new input,
something like a command and arguments, or a workload script staged alongside the classpath.

That is a real expansion of scope, and it has a sharp edge: a profiling workload is project-specific and
arbitrary code. It would run inside the worker with the task role's permissions. Anything accepted here
needs the same scrutiny as `agentImageUri` got — a client that can specify what runs in the worker is
choosing what executes with those permissions. See
[`migration-from-direct-ecs-access.md`](migration-from-direct-ecs-access.md) for why that parameter was
deleted rather than deprecated.

Alternative that avoids the expansion: keep the profiling run client-side as today, and use suspend/resume
only to hold the worker warm while the client does it. Loses the accuracy argument — the profile is
collected on the client's architecture, which for an `arm64` cell built from an `x86_64` workstation is the
wrong one entirely — but adds no new input and no new trust boundary.

**Recommendation: start with the second.** It delivers points 2 and 3 above with no contract or security
change, and it makes the accuracy question a separate, later decision with its own design.

## Hazards specific to resuming from a snapshot

AWS is explicit that *"some applications may need to integrate with service-provided hooks when resuming
from snapshots, such as to re-establish network connections or refresh cached values"*. Three that apply
directly:

**Expired credentials.** The agent holds AWS credentials for S3. A MicroVM may be suspended for up to 8
hours; task role credentials do not last that long. The resume hook must re-resolve them, and the agent
currently caches an `S3Client` built once at startup. This is the same failure shape as
`AwsSigV4Signer` caching `AwsCredentials` in `express-compute-control-plane`, noted in
[`sigv4-client-signing.md`](sigv4-client-signing.md) — fine for a short-lived process, wrong for one that
outlives an hour.

**Seeded randomness.** The skill guidance for MicroVMs is to reseed CSPRNGs on resume, because snapshots
share memory state. This is the same bug class as the static `SecureRandom` that `native-image` refused to
bake into the control plane image — identical reasoning, different mechanism. The agent does not currently
generate identifiers, but anything added must not.

**Open connections.** A suspended agent's CloudWatch Logs and S3 connections are dead on resume. The
resume hook must rebuild them rather than discovering it lazily mid-upload.

## Scope: what stays on Fargate

Lambda MicroVMs are **ARM64 only**, and GraalVM does not cross-compile, so `x86_64` cells cannot move.
This backend is additive: a build matrix spanning both architectures would launch a MicroVM for the `arm64`
cell and a Fargate task for `x86_64`.

That has a consequence worth stating: the two cells of one build would then have different timing
characteristics and different failure modes, and the per-cell log labelling that commit `7782771` fixed
becomes more important rather than less.

## What would need building

| | |
|---|---|
| `MicroVmLauncher` | beside `EcsTaskLauncher`; `RunMicrovm`, suspend, resume, terminate |
| `MicroVmSupervisor` | beside `EcsTaskSupervisor`; polls `GetMicrovm` rather than `DescribeTasks` |
| MicroVM image build | a `MicrovmImage` from the agent Dockerfile; versioned, 1-week minimum retention |
| Backend selection | per cell, by architecture; config-driven, defaulting to Fargate |
| Auth token handling | tokens expire at **60 minutes max**, so a long suspend needs refresh, held server-side so the client never sees one |
| Resume hooks | credential refresh, connection rebuild |
| Idle policy | `maxIdleDurationSeconds` and `suspendedDurationSeconds` so an abandoned MicroVM self-terminates rather than accruing snapshot storage |

That last row matters for the same reason the reaper exists: a client that dies must not leave paid
resources behind. The reaper currently stops ECS tasks; it would need to terminate MicroVMs too.

## Open questions

- **Snapshot write/read cost for a ~8 GB memory state**, against the ~28 s of provisioning and pull it
  avoids. This determines whether suspend beats terminate-and-relaunch for the ordinary rebuild case, and
  nothing in the pricing pages gives a figure we can apply without measuring.
- **Whether `native-image` tolerates being snapshotted mid-process at all.** The design assumes suspension
  happens *between* phases, with no build running — worth confirming rather than assuming, since a builder
  holding memory-mapped files across a snapshot is exactly the sort of thing that behaves surprisingly.
- **Whether MicroVM concurrency limits suit bursty matrix builds.** `RunMicrovm` is 5 TPS with a burst of
  5, and account memory is a pooled quota across `RUNNING` *and* `SUSPENDED` instances — so suspended
  workers consume capacity that running ones need.
- **Whether the arm64-only restriction makes a mixed backend worth the operational cost** of maintaining
  two launchers, two supervisors, and two image pipelines to move one of two cells.
