# Design: Lambda MicroVM build backend — Phase 1 (no suspend/resume)

## Status: Implementing

Supersedes the planning layer of [`microvm-build-backend.md`](microvm-build-backend.md) and
[`microvm-backend-integration.md`](microvm-backend-integration.md) for Phase 1 scope specifically.
Those two documents remain the record of *why* (motivation, cost model, suspend/resume case) and
are not rewritten here — this document is the one that gets implemented, grounded in the SDK and
code as they actually are today, not as sketched.

**What "Phase 1" means, concretely:** `arm64` cells run on a Lambda MicroVM instead of a Fargate
task. The MicroVM is created, runs one `native-image` compile, and is terminated — no suspend, no
resume, no idle-then-resume state. `x86_64` is untouched. The value delivered is VM-level isolation
and (per `microvm-build-backend.md`'s cost model) a resource envelope that fits the measured
workload almost exactly, not suspend/resume's PGO story — that stays Phase 2, deliberately deferred,
with its own open questions in `microvm-build-backend.md` still unresolved.

## Why this document exists separately from the two it builds on

Both prior documents were written before any code against the `lambdamicrovms` SDK existed in this
repository. Three things they could only sketch are now verified against the actual
`software.amazon.awssdk:lambdamicrovms:2.55.11` jar (the version this project already pins) and the
actual current code:

1. **The hook-timeout problem they flagged and partially resolved is real, and the resolution they
   landed on (fork-and-return, poll a side channel) is already implemented** — not for build
   execution, but for the adjacent "build the agent's MicroVM image" admin flow. `MicroVmImageManager`
   already configures `run` with a 30-second timeout (`RUN_HOOK_TIMEOUT_SECONDS`) against the
   platform's 60-second ceiling, with the comment *"the agent forks the build and returns
   immediately."* That comment describes code that does not exist yet — the agent has no HTTP server
   of any kind, hook or otherwise, confirmed by grepping the whole `scaleout-build-agent` module for
   `/run`, `/ready`, `/validate`, or any `HttpServer` — but the *shape* of the fix is already decided
   and half-wired on the control-plane side. This document finishes the other half.

2. **`RunMicrovmResponse` already contains `endpoint()` and `microvmId()` directly** — confirmed via
   `javap` against the real class file — which removes one piece of design uncertainty: no separate
   `GetMicrovm` call is needed just to learn the endpoint after launch.

3. **`MicrovmState` has no `FAILED` value** (`PENDING, RUNNING, SUSPENDED, SUSPENDING, TERMINATED,
   TERMINATING, UNKNOWN_TO_SDK_VERSION` — confirmed via `javap`). A launch failure must be detected
   from `RunMicrovm` throwing, not from polling a state value that will never say `FAILED`. This
   shapes the cell-state mapping below and is not something the prior two documents could have known
   without running the SDK.

## Scope boundary, restated precisely

| | Fargate (unchanged) | MicroVM (this document) |
|---|---|---|
| Architectures | `x86_64`, `arm64` | `arm64` only |
| Launch call | `ecs:RunTask` | `lambda-microvms:RunMicrovm` |
| Poll call | `ecs:DescribeTasks` | `lambda-microvms:GetMicrovm` |
| Stop call | `ecs:StopTask` | `lambda-microvms:TerminateMicrovm` |
| Lifecycle | launch → run → exit → gone | launch → run → **terminate explicitly** → gone |
| Suspend/resume | n/a | **not used in Phase 1** — `idlePolicy` set to terminate-on-idle, not suspend |

A build spanning both architectures launches a Fargate task for the `x86_64` cell and a MicroVM for
the `arm64` cell, concurrently, exactly as today's matrix already launches two independent Fargate
tasks concurrently. Nothing about `BuildService.start()`'s per-cell loop structure changes; what
changes is which launcher a given cell's loop iteration calls.

## Backend selection: by architecture, not configuration

`microvm-backend-integration.md` sketched a configurable `arm64Backend = microvm|fargate|auto` CDK
context key with an `AUTO` fallback. **Phase 1 does not build that.** Reasons, found by actually
tracing what `AUTO` would need:

- `AUTO`'s "check whether a MicroVM image with the configured version exists before launching, fall
  back to Fargate silently if not" requires a `GetMicrovmImage` call (or a cached result of one) on
  every `arm64` cell launch, adding a new failure mode (image lookup fails or is slow) to a path that
  does not need it once there is exactly one way to build the image and one place that triggers it
  (the existing `/admin/microvm-images` endpoint, which this document does not change).
- A silent fallback from MicroVM to Fargate is observability debt: a developer sees `arm64` cells
  sometimes on Fargate and sometimes on MicroVM with no signal why, which is exactly the kind of
  non-determinism `CONTRIBUTING.md`'s "fail loudly rather than fall through" convention warns against.

**What Phase 1 does instead:** one boolean config value, `scaleout.microvm.enabled` (default
`false`). When `true` and `config.microVm().executionRoleArn()`/`imageArn()` are present, every
`arm64` cell uses the MicroVm backend. When `false`, or when the required MicroVM config is absent,
every `arm64` cell uses Fargate — identical to today's behavior. No per-build override, no
per-cell choice beyond architecture: this is deployment policy, the same category as
`fargateCapacityStrategy`.

If a deployment wants to migrate `arm64` from Fargate to MicroVM, that is one SSM/config flip and
one redeploy — a hard cutover, not a gradual one. Given Phase 1 has no suspend/resume and therefore
no state carried between builds, there is nothing a gradual rollout would be protecting.

### Addendum: `backendPreference`, a per-build override added after real pricing data

The reasoning above still holds for what it actually argued against: a *silent*, *image-existence-
probing* `AUTO` that could leave an `arm64` cell on a different backend from one build to the next
with no visible signal why. `BuildSpec.backendPreference` is not that. It is a client-supplied,
**explicitly validated** choice — `auto` (this section's own behavior, unchanged, still the
default), `fargate` (force Fargate even when the deployment defaults `arm64` to MicroVM), or
`microvm` (force MicroVM; **rejected outright at build-creation time**, not silently substituted,
if the deployment does not have it enabled or if `x86_64` is also requested). No image-existence
probe is added — `microvm` is only accepted when `config.microVm()` already reports the deployment
as fully configured, which is a config read, not an AWS API call.

What changed the calculus from "deployment policy, full stop" to "deployment policy, with an
explicit per-build override": real pricing data, pulled from the AWS Price List API rather than
estimated. In `us-east-1`, MicroVM's per-unit rates run roughly **3–3.7x** Fargate's for the same
architecture (ARM): $0.0000276944 vs $0.000008994 per vCPU-second, $0.0000036667 vs $0.0000009889
per GB-second. MicroVM's baseline/burst billing model is a genuine win for bursty, idle-heavy
workloads — you only pay the higher rate for the vCPU-seconds/GB-seconds actually consumed above
baseline. It is not a win for a workload with no idle period to exploit. `native-image` is
measurably that second kind: `fargate-task-resource-usage.md` documents it saturating all 4 vCPUs
and holding near-peak RSS for the compile's entire duration. Modeled against this project's own
measured 233-dependency compile (5–9 min at 4 vCPU/8 GiB, used throughout), the same work costs
roughly $0.0184 on Fargate and $0.0589 on MicroVM — about 3.2x, for identical output. A deployment
that flips `microvmEnabled=true` for its latency or isolation benefits would otherwise silently
make every `arm64` build in that category meaningfully more expensive, with no way for a developer
who understands their own build's resource profile to opt back out, or for a developer on a
Fargate-default deployment to deliberately opt in once they *do* want MicroVM's actual benefit
(the real dynamic-vertical-burst case: a build whose resource needs vary across phases rather than
sitting at peak throughout, where baseline+burst billing could beat Fargate's flat rate for the
lighter phases — not yet validated with real phase-level profiling data, so this remains a reason
to allow opting in, not yet a reason to default to it).

This is Tier 2 of a larger, intentionally staged plan. Tier 4 — percentage-based rollout and
group/tag-based quota allocation (mirroring the RBAC model `KubeMicroVM PRO` uses for its own
MicroVM access tiers) — is deliberately not built here; it needs real usage/cost data from Tier 2
in production first, and is tracked as a roadmap item, not a Phase 1 deliverable.

## New interface: `CellLauncher`

`microvm-backend-integration.md` proposed this and it is still the right shape, taken essentially
unchanged — confirmed correct against `BuildService.start()`'s actual per-cell loop body, which
today inlines `TaskDefinitionRegistrar.registerIfChanged()` + `EcsTaskLauncher.runTask()` directly.

```java
package ai.codriverlabs.scaleoutbuild.ecs;

/** One cell's launch/poll/stop operations, backend-agnostic. */
public interface CellLauncher {

    /** Launches one cell. Throws on failure; does not return a FAILED handle. */
    CellHandle launch(CellLaunchRequest request) throws CellLaunchException;

    /** Polls a launched cell's current state. */
    CellPollResult poll(CellHandle handle);

    /** Stops a running cell. Best-effort: already-stopped is not an error. */
    void stop(CellHandle handle, String reason);
}
```

`CellLaunchRequest` replaces the ad-hoc parameter list `BuildService.start()` currently builds
inline (cluster settings, container settings, build kind, architecture, environment) with one
record, so both launchers receive the same shape and `BuildService` does not need an `instanceof`
to decide what to pass.

`CellHandle` is a sealed interface, exactly as sketched:

```java
public sealed interface CellHandle {
    record FargateHandle(String taskArn) implements CellHandle {}
    record MicroVmHandle(String microvmId, String endpoint) implements CellHandle {}
}
```

`CellPollResult` is a small record carrying the mapped `CellState`, an optional exit code, and an
optional failure reason — the same three things `BuildService.refreshFromEcs()` already extracts
from `DescribeTasks`, generalized so `MicroVmCellLauncher.poll()` returns the equivalent from a
different source (see "Build status reporting" below for what that source is).

### Why this is a `CellLauncher` per cell, not a `BuildBackend` per build

The Maven plugin already has a `BuildBackend` interface (`scaleout-build-maven-plugin`'s
`BuildBackend.runCells`) — a different, unrelated interface in a different module, for a different
problem (local-vs-remote cell splitting on the client side, pre-dating the control plane entirely).
Naming the new control-plane-side interface `CellLauncher` rather than reusing or shadowing
`BuildBackend` avoids a false association between two interfaces that happen to both be about
"running a cell" but solve different problems in different processes.

## `BuildRecord.CellRecord`: widening `taskArn` without a flag day

Confirmed by reading `BuildRecord.CellRecord` and `BuildItems` directly: today `taskArn` is a plain
`String` field, serialized two ways — once inside the JSON `document` blob (via `CellRecord`'s own
Jackson shape), and once more, **redundantly and deliberately**, as a flat DynamoDB `StringSet`
attribute `taskArns`, read by `BuildReaperHandler` specifically so the reaper Lambda never has to
parse the service's JSON document shape (see `BuildItems.ATTR_TASK_ARNS`'s javadoc, which states
this explicitly).

Both places need to learn about a second handle shape. The migration is additive, not a schema
rewrite:

**`CellRecord` gains two nullable fields**, `backend` (`"FARGATE"` or `"MICROVM"`, defaulting to
`"FARGATE"` when absent so every existing record deserializes unchanged) and `microvmId` (null for
Fargate cells). `taskArn` stays exactly as it is for Fargate cells; for MicroVM cells, the launcher
stores the endpoint in a new `microvmEndpoint` field and the microvm identifier in `microvmId`.
Nothing is removed, nothing is renamed, so every record written before this change deserializes
with `backend == "FARGATE"` and the new fields null — no migration script, no dual-read window
needed, because old data is simply data where the new fields happen to be absent.

**`BuildItems`'s flat `taskArns` attribute becomes two flat attributes**: `taskArns` (unchanged,
Fargate cells only) and `microvmIds` (new, MicroVM cells only — same `StringSet` shape). The reaper
reads both and dispatches:

```java
// BuildReaperHandler.reapOne, extended
for (String taskArn : taskArns) {
    ecs.stopTask(b -> b.cluster(clusterArn).task(taskArn).reason(reason));
}
for (String microvmId : microvmIds) {
    microvms.terminateMicrovm(b -> b.microvmIdentifier(microvmId));
}
```

This preserves `BuildItems.ATTR_TASK_ARNS`'s own stated design intent — "the reaper's contract to
'state, timestamps, task ARNs' and nothing more" — by extending that contract with one more flat
field of the same shape, rather than making the reaper parse the document to find out which backend
a cell used.

## `FargateCellLauncher`: extracting, not rewriting

`EcsTaskLauncher`/`TaskDefinitionRegistrar`'s actual logic does not change at all. `FargateCellLauncher`
is a thin adapter implementing `CellLauncher` by delegating to the existing two classes exactly as
`BuildService.start()` already calls them today — this is a refactor-by-extraction, not new logic,
and should be verifiable by a test asserting identical `RunTaskRequest`/`StopTaskRequest` shapes
before and after the extraction.

## `MicroVmCellLauncher`

### Launch

```java
public CellHandle launch(CellLaunchRequest request) throws CellLaunchException {
    RunMicrovmResponse response;
    try {
        response = microvms.runMicrovm(b -> b
                .imageIdentifier(config.imageArn())
                .imageVersion(config.imageVersion())
                .executionRoleArn(config.executionRoleArn())
                .idlePolicy(i -> i
                        .maxIdleDurationSeconds(60)      // terminate promptly once the hook returns
                        .suspendedDurationSeconds(0)      // Phase 1: no suspension at all
                        .autoResumeEnabled(false))
                .maximumDurationInSeconds(maxDurationSecondsFor(request)) // hard ceiling, see below
                .runHookPayload(SdkBytes.fromUtf8String(runHookPayloadJson(request)))
                .egressNetworkConnectors(List.of(config.vpcEgressConnectorArn()))
                // No ingress connector: the control plane never calls into the MicroVM (see
                // "Build status reporting" below for why there is nothing to call).
                .build());
    } catch (SdkException e) {
        throw new CellLaunchException("RunMicrovm failed: " + e.getMessage(), e);
    }
    return new CellHandle.MicroVmHandle(response.microvmId(), response.endpoint());
}
```

`maximumDurationInSeconds` is a field this document's two predecessors did not have, because it
only exists to inspect via `javap` — confirmed present on `RunMicrovmRequest.Builder`. It is the
direct MicroVM equivalent of the Fargate path's container-wide `coreutils timeout` wrapper
(`AgentMain`'s entrypoint; see `BuildReaperHandler`'s own javadoc for why that wrapper exists and
what it closed). Setting it to `(overallTimeoutMinutes + marginMinutes) * 60`, mirroring the
existing `defaultCellTimeoutMinutes` + margin reasoning in `ControlPlaneConfig.Ecs`, gives the
MicroVM path the same two-layer timeout structure Fargate already has — an inner compile-specific
timeout (unchanged, enforced by the agent's own existing timeout logic) and an outer backstop
enforced by the platform rather than by a process wrapper.

### Why no ingress connector, and what that implies for status polling

`microvm-backend-integration.md`'s own revised execution model (its "Resolving question 3" section)
concluded the control plane should never call into the MicroVm's endpoint — the agent reports
completion via a side channel instead, with the control plane as a passive reader. That conclusion
is adopted here unchanged, and it directly explains why `RunMicrovmRequest` has no field for the
control plane's own identity or callback URL: there is nothing for the agent to call back *to*. The
`endpoint()` the SDK returns is for the agent's own hooks (`/ready`, `/validate` during image build;
nothing at runtime in Phase 1), not for the control plane to poll.

### `runHookPayload`

The same environment variables `AgentEnvironment` already builds for the Fargate path — build ID,
staging path, build kind, architecture, timeout minutes, S3 bucket — serialized as JSON rather than
as ECS `KeyValuePair`s, because `runHookPayload` is a byte payload the agent's hook reads from its
own request body, not an environment variable mechanism. `AgentEnvironment.Builder` already
produces exactly these values; this needs a second output method (`toJson()` alongside the existing
`build()` which returns `List<KeyValuePair>`) rather than a parallel value-construction path, so the
two backends cannot drift on what values a cell receives.

### Poll

```java
public CellPollResult poll(CellHandle handle) {
    MicroVmHandle h = (MicroVmHandle) handle;
    // Status comes from the status.json object the agent writes to S3, NOT from GetMicrovm --
    // GetMicrovm's MicrovmState has no FAILED value and would misreport a crashed build as
    // indistinguishable from "still running" (TERMINATED fires either way).
    Optional<AgentStatusReport> report = statusReader.read(buildId, cellId);
    if (report.isPresent()) {
        return CellPollResult.from(report.get());  // SUCCEEDED or FAILED, with exit code + reason
    }
    // No status.json yet. Fall back to GetMicrovm purely to distinguish "still running" from
    // "the platform itself killed it" (idle timeout, maximumDurationInSeconds exceeded) --
    // the one thing status.json cannot report, because the agent never got to write it.
    GetMicrovmResponse r = microvms.getMicrovm(b -> b.microvmIdentifier(h.microvmId()));
    return switch (r.state()) {
        case PENDING, RUNNING -> CellPollResult.running();
        case TERMINATED, TERMINATING ->
            CellPollResult.failed(null, "MicroVM terminated before writing a status report "
                    + "(stateReason: " + r.stateReason() + ") -- likely the idle or maximum "
                    + "duration limit, not a build failure");
        case SUSPENDED, SUSPENDING -> CellPollResult.failed(null,
                "MicroVM suspended unexpectedly -- Phase 1 does not use suspend/resume, so this "
                + "indicates a platform-side state this launcher does not expect");
        default -> CellPollResult.running();
    };
}
```

This is the one place where this document materially diverges from `microvm-backend-integration.md`'s
revised model, and the divergence is intentional: that document proposed polling S3 for
`status.json` **exclusively**. Doing that alone cannot distinguish "still compiling" from "the
platform silently killed the MicroVM for exceeding `maximumDurationInSeconds` before the agent could
write anything" — both look identical (no `status.json`) to a pure-S3 poller. `GetMicrovm` as a
secondary, fallback-only check closes that gap at the cost of one extra API call per poll cycle when
`status.json` is absent, which is exactly the condition (no report yet) where the extra call is
cheap because the MicroVM is either genuinely still running (common, fast call) or already gone
(rare, and the only case that needed resolving).

### `status.json`

Format matches `microvm-backend-integration.md`'s sketch exactly — no change needed there, since
that part of the design was already sound:

```json
{
  "buildId": "01J8...",
  "cell": "NATIVE/ARM64",
  "state": "SUCCEEDED",
  "exitCode": 0,
  "artifacts": [{"path": "hello-native", "sha256": "...", "sizeBytes": 13241624}],
  "completedAt": "2026-10-08T03:00:00Z"
}
```

Written by the agent to `s3://<staging-bucket>/<staging-path>/status.json` — the same staging prefix
`StagingLayout` already computes for every other input/output of this cell, so no new bucket, no
new prefix convention, and `S3Io`/`StagingLayout`'s existing path-resolution logic needs no change
to accommodate it.

### Stop

```java
public void stop(CellHandle handle, String reason) {
    MicroVmHandle h = (MicroVmHandle) handle;
    try {
        microvms.terminateMicrovm(b -> b.microvmIdentifier(h.microvmId()));
    } catch (SdkException e) {
        // Already terminated, or transient -- same tolerance EcsTaskLauncher.stopTask() already
        // has for StopTask failing on an already-stopped task.
        LOG.warn("TerminateMicrovm failed for {}: {}", h.microvmId(), e.getMessage());
    }
}
```

## Agent: the actually-new component

**This is the single largest piece of genuinely new code**, and the reason both prior design docs'
"what would need building" tables listed it without anyone having started it. The agent today
(`AgentMain`, confirmed by direct reading) is a one-shot process: read environment variables, run
one compile, exit. A MicroVM's `run` hook needs an HTTP endpoint to receive the `runHookPayload`
POST, which nothing in `scaleout-build-agent` currently provides.

### Shape: fork-and-return, status.json on completion

```java
public final class AgentHookServer {

    private final HttpServer server; // com.sun.net.httpserver.HttpServer -- JDK built-in,
                                       // same choice ControlPlaneHttpClient's test suite already
                                       // made for exactly this "no new dependency" reason

    public static void main(String[] args) throws IOException {
        new AgentHookServer().start(9000);
    }

    void start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/ready", this::ready);     // image-build time only
        server.createContext("/validate", this::validate); // image-build time only
        server.createContext("/run", this::run);           // runtime, Phase 1's actual work
        server.start();
    }

    private void run(HttpExchange exchange) throws IOException {
        byte[] payload = exchange.getRequestBody().readAllBytes();
        exchange.sendResponseHeaders(200, -1);  // return within the 30s budget, before compiling
        exchange.close();

        // Fork: the hook has already returned. Compile happens on a daemon thread so the JVM
        // can still exit cleanly if something goes very wrong, same shutdown-safety shape
        // AgentMain's single-shot design already has.
        Thread compile = new Thread(() -> runCompileAndWriteStatus(payload), "scaleout-build-compile");
        compile.setDaemon(false);  // NOT daemon -- a daemon thread would let the JVM exit before
                                    // the compile finishes if main() returns, and main() does
                                    // return immediately after server.start() in this model.
        compile.start();
    }

    private void runCompileAndWriteStatus(byte[] payloadJson) {
        AgentConfig config = AgentConfig.fromHookPayload(payloadJson); // new entry point,
                                                                         // parallel to fromEnvironment()
        int exitCode = AgentMain.runWithDirectS3Io(config, BuildLog.defaultLog(), S3Client.create());
        writeStatusJson(config, exitCode);
    }
}
```

`AgentMain.runWithDirectS3Io` is reused **completely unchanged** — this was confirmed by reading it
in full: it already does exactly what the hook needs (download staged inputs via `S3Io`, build via
the same `BuildEnvironment`/`NativeImageBuildExecutor` the Fargate path uses, upload artifacts via
`S3Io`, return an exit code), parameterized entirely by an `AgentConfig`. The only new agent-side
code is: the HTTP server itself, a new `AgentConfig.fromHookPayload(byte[])` factory method
alongside the existing `fromEnvironment()` (same fields, different source), and `writeStatusJson`,
which did not exist in any form before.

### `/ready` and `/validate`

`MicroVmImageManager.buildHooks()` already configures these with specific timeouts
(`READY_HOOK_TIMEOUT_SECONDS = 120`, `VALIDATE_HOOK_TIMEOUT_SECONDS = 180`) and already documents
what they must do in its own javadoc. The agent-side implementations are new but trivial relative to
`/run`:

```java
private void ready(HttpExchange exchange) throws IOException {
    // Phase 1: no warm state to establish before the first build, so always ready immediately.
    exchange.sendResponseHeaders(200, -1);
    exchange.close();
}

private void validate(HttpExchange exchange) throws IOException {
    // Runs a synthetic build so the platform can sample hot memory pages for snapshot prefetch --
    // MicroVmImageManager's own javadoc on VALIDATE_HOOK_TIMEOUT_SECONDS already documents why
    // this must not be a no-op: "the agent runs a synthetic build here, so this must exceed a
    // minimal compile."
    runSyntheticCompile();
    exchange.sendResponseHeaders(200, -1);
    exchange.close();
}
```

The synthetic compile is a minimal `native-image` invocation against a trivial bundled class —
intentionally not the user's actual project, since `/validate` runs once per image version, long
before any real build's inputs exist.

### Packaging: a second entrypoint, not a second module

The hook server needs to be the agent image's entrypoint when running as a MicroVM, while
`AgentMain.main()` stays the entrypoint for the Fargate/ECS path — same jar, same shaded artifact,
different `Dockerfile`/`CMD`. `scaleout-build-agent`'s existing `jib` profile (confirmed read in
full) already builds a self-contained image for the Mandrel base; the MicroVM image build
(`MicroVmImageManager.triggerBuild`) submits a *separate* code artifact ZIP containing its own
`Dockerfile` referencing the agent's published image by digest — so the Dockerfile for that ZIP sets
`CMD ["java", "-cp", "agent.jar", "ai.codriverlabs.scaleoutbuild.build.agent.AgentHookServer"]`
instead of `AgentMain`. No new Maven module, no new jar — one new class, one new Dockerfile (which
already has a dedicated home: the image-build pipeline step that constructs the code-artifact ZIP,
not committed to this repo as a template today and worth adding as
`scaleout-build-agent/src/main/resources/microvm/Dockerfile`).

## `BuildService` integration points

Three methods change, each by the smallest amount that correctly dispatches by backend:

**`start()`**: the per-cell loop's launch call becomes `cellLauncher(architecture).launch(request)`
instead of the inlined `registrar.registerIfChanged()` + `launcher.runTask()` pair.
`cellLauncher(Architecture)` is a two-line method returning the MicroVm launcher for `ARM64` when
`config.microVm().enabled()` and the required MicroVM config is present, the Fargate launcher
otherwise.

**`refreshFromEcs()`** (renamed `refreshCellStates()`, since it no longer only talks to ECS): the
single `ecs.describeTasks()` batch call becomes two batch operations — one `describeTasks` call for
every non-terminal Fargate-backend cell (unchanged shape, same batching), and one loop calling
`MicroVmCellLauncher.poll()` per non-terminal MicroVm-backend cell (no batch `GetMicrovm` exists in
the SDK, confirmed by its absence from the operation list `javap` printed, so this is N calls for N
MicroVM cells rather than one batched call — acceptable because N is bounded by the matrix size,
typically 1 or 2).

**`stopRunningCells()`**: the single `launcher.stopTask()` call per cell becomes
`cellLauncher(cell.getBackend()).stop(handle, reason)`, dispatching on the stored `backend` field
rather than architecture, since a cell's backend was fixed at launch time and must stop the same way
regardless of what the *current* config says (a config flip mid-build must not change how an
in-flight build's cells are stopped).

## Config additions

```java
interface MicroVm {
    // existing: adminRoleArns(), buildRoleArn(), artifactsBucketName(), executionRoleArn()

    /** Whether arm64 cells use the MicroVM backend. Default false: opt-in, same as the existing
     *  admin image-build surface is opt-in via adminRoleArns(). */
    @WithDefault("false")
    boolean enabled();

    /** ARN of the MicroVM image to launch. Required when enabled() is true. */
    Optional<String> imageArn();

    /** Version of the image to launch. Required when enabled() is true. */
    Optional<String> imageVersion();

    /** VPC egress connector ARN, for S3 and CloudWatch Logs access. Required when enabled()
     *  is true. */
    Optional<String> vpcEgressConnectorArn();
}
```

`executionRoleArn()` already exists for the admin image-build flow's runtime role; it is reused
unchanged for the build-execution MicroVMs, since both need identical permissions (S3 staging
bucket read/write, CloudWatch Logs write) and provisioning two separate roles for the same
permission set would be duplication with no isolation benefit — they are the same trust boundary.

## CDK additions

The existing MicroVM block in `ControlPlaneInfraStack` (confirmed read to its current end) stops
right after wiring the admin-build-only IAM actions
(`CreateMicrovmImage`/`GetMicrovmImage`/`GetMicrovmImageBuild`/`TerminateMicrovm`) and environment
variables. This document adds, inside the same `if (!microvmAdminRoleArn.isBlank())` block (or a
new, independently-gated block if build-execution should be enablable without the admin surface —
open question, see below):

```java
serviceFunction.addToRolePolicy(PolicyStatement.Builder.create()
        .effect(Effect.ALLOW)
        .actions(List.of(
                "lambda-microvms:RunMicrovm",
                "lambda-microvms:GetMicrovm"))
        // TerminateMicrovm is already granted above for the admin flow's own cleanup;
        // reused here rather than granted twice.
        .resources(List.of(
                String.format("arn:aws:lambda:%s:%s:microvm-image:scaleout-build-agent",
                        this.getRegion(), this.getAccount())))
        .build());

serviceFunction.addEnvironment("SCALEOUT_MICROVM_ENABLED",
        String.valueOf(this.getNode().tryGetContext("microvmEnabled") != null));
serviceFunction.addEnvironment("SCALEOUT_MICROVM_IMAGE_ARN", /* from context or SSM */);
serviceFunction.addEnvironment("SCALEOUT_MICROVM_IMAGE_VERSION", /* from context or SSM */);
serviceFunction.addEnvironment("SCALEOUT_MICROVM_VPC_EGRESS_CONNECTOR_ARN", /* from context */);
```

The reaper Lambda needs `lambda-microvms:TerminateMicrovm` added to its own role (currently holds
only `ecs:StopTask`/`ecs:DescribeTasks`, confirmed by reading `ControlPlaneInfraStack`'s reaper IAM
block) — the exact same action already granted to the service function for the admin flow, granted
a second time to a second principal because the reaper runs as its own function with its own role.

## What is explicitly still not in Phase 1

Unchanged from `microvm-backend-integration.md`'s own list, restated because it still holds:
suspend/resume for PGO, MicroVM for `x86_64` (platform does not support it), per-build idle-policy
override, auth-token forwarding to any client. Also newly out of scope for Phase 1 specifically,
found while writing this document rather than assumed in advance: the *silent, image-probing*
`AUTO` backend-selection mode originally sketched in `microvm-backend-integration.md` (see
"Backend selection" above for why), and any gradual/canary rollout mechanism — the latter is still
out of scope for Phase 1 proper, but is now a named roadmap item (Tier 4, percentage/group-based
rollout) rather than an open question, following the per-build `backendPreference` override added
after Phase 1's own real-AWS verification (see "Backend selection"'s addendum).

## Open questions before implementation starts

1. **Regional availability**, carried over unresolved from `microvm-build-backend.md` — still not
   checked against the actual deployed region. This blocks nothing in writing the code, but blocks
   actually deploying it. Check with `aws lambda-microvms list-managed-microvm-images --region
   <deployed-region>` before the first real deploy.

2. **Whether build-execution should be gated by its own config key independent of
   `microvmAdminRoleArn`**, or share that same gate. Admin (image building) and execution (running
   builds on MicroVMs) are genuinely separable concerns — a deployment might want to build images
   centrally but not yet trust MicroVMs for real builds, or vice versa once Phase 2 exists. Leaning
   toward separate gates (`microvmAdminRoleArns` for who can trigger image builds,
   `microvmEnabled`+`imageArn`/`imageVersion` for whether builds use the result), but not settled
   here — decide at implementation time once the CDK context-key shape is being written for real,
   not sketched.

3. **VPC egress connector provisioning** — whether this repository's CDK stack creates the connector
   resource itself, or expects one to already exist and only accepts its ARN as config. The existing
   MicroVm config interface already has no `vpcEgressConnectorArn` optionality precedent to copy
   from (`buildRoleArn`/`executionRoleArn`/`artifactsBucketName` are all externally-provisioned
   ARNs/names passed in, never created by this stack), which argues for the same pattern — accept an
   ARN, do not provision the connector — but this needs confirming against whatever the actual VPC
   setup looks like in a target deployment before the CDK code is written.

## Implementation order

1. `CellLauncher`/`CellHandle`/`CellLaunchRequest`/`CellPollResult` interfaces in `scaleout-build-ecs`,
   plus `FargateCellLauncher` as a pure extraction of `BuildService`'s existing inline Fargate logic
   — verifiable with a test asserting identical `RunTaskRequest` shape before/after, zero behavior
   change expected.
2. `BuildRecord.CellRecord` + `BuildItems` widened per "widening `taskArn` without a flag day" above.
   Verifiable with a test deserializing a pre-existing (fixture) JSON document shape and asserting
   `backend == FARGATE`, `microvmId == null`.
3. Agent: `AgentConfig.fromHookPayload`, `AgentHookServer`, `writeStatusJson`. Verifiable standalone
   with a loopback-`HttpServer`-based test posting a payload and asserting a `status.json` object
   lands in a local directory standing in for S3 — same "exercise the real wire path" pattern
   `ControlPlaneClientTest` already established for the shared HTTP client.
4. `MicroVmCellLauncher`, wired against a mocked `LambdaMicrovmsClient` the same way
   `MicroVmImageManagerTest` already mocks it for the admin flow.
5. `BuildService` dispatch changes (`cellLauncher(Architecture)`, the two integration points in
   `start()`/`refreshCellStates()`/`stopRunningCells()`).
6. Reaper dispatch change.
7. CDK additions.
8. End-to-end against real AWS, gated on open question 1 (regional availability) actually being
   confirmed first.

## Related documents

- [`microvm-build-backend.md`](microvm-build-backend.md) — motivation, cost model, Phase 2
  suspend/resume case and its open questions (unchanged, still the authority on *why* and on
  Phase 2).
- [`microvm-backend-integration.md`](microvm-backend-integration.md) — the implementation sketch
  this document supersedes for Phase 1, including the hook-timeout self-correction this document
  carries forward as settled rather than re-deriving.
- [`microvm-platform-fit.md`](microvm-platform-fit.md) — AgentCore client placement, unaffected by
  this document (client-side, not worker-side).
- [`HANDOVER.md`](HANDOVER.md) — item 5, "MicroVM backend is designed, not built." This document is
  what changes that line to "Phase 1 implementing."
