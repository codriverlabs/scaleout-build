# Running on MicroVM platforms: AgentCore client, MicroVM workers

Two separate questions that an earlier draft of this document conflated, to its cost:

1. **Where the client runs** — the thing invoking Maven with this plugin. Intended target: an agent on
   Amazon Bedrock AgentCore Runtime, plausibly a Harness loop.
2. **Where the `native-image` work runs** — the build workers the control plane launches. Today Fargate;
   Lambda MicroVMs are a candidate.

Their requirements differ by an order of magnitude, so a limit that blocks one may be irrelevant to the
other. Measured figures come from [`fargate-task-resource-usage.md`](fargate-task-resource-usage.md).

```
AgentCore Runtime (agent + Maven + this plugin)     modest: HTTP, uploads, SSE
        │  HTTPS + SigV4
        ▼
Control plane Lambda (orchestrator)                 384 MB
        │  launches
        ▼
Build workers (native-image)                        4 vCPU, 5.3-5.6 GB peak RSS
        ├── Fargate            x86_64 and arm64
        └── Lambda MicroVM     arm64 only, suspend/resume
```

## The client on AgentCore Runtime

AgentCore Runtime microVMs cap at **2 vCPU / 8 GB per session**, not adjustable
([quotas](https://docs.aws.amazon.com/bedrock-agentcore/latest/devguide/bedrock-agentcore-limits.html)).

That is comfortable for a client. Its work is: resolve the classpath, run framework augmentation, upload
inputs, hold an SSE connection, download artifacts. The heaviest part is Quarkus augmentation of a
233-jar application, which is a normal Maven JVM rather than a `native-image` builder — nothing close to
the 5.6 GB the *worker* needs.

Two limits that do apply:

- **2 GB container image.** A client image needs a JDK and Maven, not Mandrel. Well inside.
- **8 hour session.** A two-architecture build of a real project takes about 6 minutes, so this only
  matters for a long-lived agent doing many builds — which is the intended shape.

## Lambda MicroVM workers, and why suspend/resume matters here

Lambda MicroVMs reach 16 vCPU / 32 GB with 8-hour lifetimes
([quotas](https://docs.aws.amazon.com/lambda/latest/dg/gettingstarted-limits.html)), so capacity is not the
constraint. **They are ARM64 only**, per the same page: *"Lambda MicroVMs support the ARM64 (AWS Graviton)
architecture."*

Since GraalVM does not cross-compile, that makes them viable for the `arm64` cell and unusable for
`x86_64`. A mixed backend follows — MicroVMs for arm64, Fargate for x86_64 — rather than a replacement.

### What suspend/resume buys: PGO

Profile-guided optimisation is inherently two-phase, and today the two phases are unrelated builds.
`BuildKind`'s own javadoc is explicit that *"running the instrumented binary against a representative
workload to collect the `.iprof` profile that `NATIVE_PGO_OPTIMIZE` consumes happens entirely outside this
tool."*

So a PGO cycle currently pays the full setup cost twice:

| Per phase | Measured |
|---|---|
| Provisioning | ~20 s (arm64) |
| Image pull | ~8 s |
| Classpath staged into the worker | 54 MB from S3 |
| `native-image` | ~3 min at 4 vCPU |

A MicroVM that suspends between phases keeps its memory *and* disk state, so the second phase resumes with
the classpath already present and page cache warm. The instrument phase's staging is paid once instead of
twice, and provisioning and pull disappear entirely for phase two.

More valuable than the byte savings: the profiling run can happen **inside the same environment** that
produced the instrumented binary and will produce the optimised one. Today it happens wherever the user
arranges, on unspecified hardware, which is a weaker basis for a profile that then shapes code generation.

### What suspend/resume buys: iterative recompilation

The same mechanism addresses the ordinary case. Dependency jars are content-addressed and stable between
builds, so today they are deduplicated in S3 but still **downloaded into each fresh worker**. A resumed
MicroVM already has them on disk.

For the measured project that is 54 MB and the tail of a ~250 s task, per build, per architecture. Whether
that is worth the lifecycle complexity depends on build frequency: negligible for a few builds a day,
material for an agent iterating continuously — which is the AgentCore use case.

## Design

A full design for this backend — lifecycle, state machine, the profiling-workload decision, resume hazards
and open questions — is in [`microvm-build-backend.md`](microvm-build-backend.md).

## What this would require

Not a configuration change. The worker lifecycle differs structurally:

- **Today:** `RunTask` → poll `DescribeTasks` → container exits → task gone. One task per cell, stateless.
- **MicroVM:** `RunMicrovm` → `SuspendMicrovm` → `ResumeMicrovm` → `TerminateMicrovm`, with an endpoint and
  auth token per instance, and state that outlives a phase.

That means a second backend alongside `EcsTaskLauncher`, and a control plane that tracks a *suspended*
cell state — which the current `CellState` enum has no notion of, since it assumes a cell runs once and
reaches a terminal state.

Also worth noting: MicroVM auth tokens expire after at most 60 minutes, so a suspend spanning longer than
that needs a token refresh rather than a stored one.

## Honest status

Nothing has been run on either platform. This is documented quotas and measured requirements placed side by
side. The PGO and recompilation arguments follow from the measurements, but the same kind of reasoning said
Quarkus support was sound before a real project found six defects in it — so treat the conclusions as a
design case to test, not a validated result.

The narrower claim I would defend: **AgentCore is a fine home for the client**, and **the ARM64 restriction
means MicroVM workers supplement Fargate rather than replace it**.
