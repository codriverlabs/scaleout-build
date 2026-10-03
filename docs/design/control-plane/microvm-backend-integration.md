# Design: Lambda MicroVM backend integration

**Status: design only. Builds on [`microvm-build-backend.md`](microvm-build-backend.md),
which covers the motivation, cost model, and open questions. This document is the
implementation design: what to build, in what order, and what the seams are.**

---

## Scope recap

Lambda MicroVMs are ARM64-only. GraalVM does not cross-compile. Therefore:

- `arm64` cells → MicroVM backend (this document)
- `x86_64` cells → Fargate (unchanged)

A build spanning both architectures runs two backends concurrently. The control plane
already launches cells independently, so the backend selection is per-cell.

**Phase 1 (this design):** `arm64` cells use MicroVM instead of Fargate. No suspend/resume.
Terminate after each build. The value is faster provisioning and VM-level isolation for
the future multi-tenant case.

**Phase 2 (future):** Suspend/resume for PGO workflows. Separate design; see
`microvm-build-backend.md` for the open questions that must be resolved first.

---

## Architecture overview

```
BuildService
  └─ CellLauncher (interface)  <── selected per cell by architecture
       ├─ FargateCellLauncher  (x86_64, existing logic extracted)
       └─ MicroVmCellLauncher  (arm64, new)
            ├─ lambda-microvms:RunMicrovm
            ├─ lambda-microvms:CreateMicrovmAuthToken  (per log-poll cycle)
            ├─ HTTP /ready hook  (wait for agent to signal ready)
            └─ lambda-microvms:TerminateMicrovm  (on completion or cancel)

CellMonitor (existing polling loop)
  └─ reads cell state from MicroVm (GetMicrovm) instead of ECS DescribeTasks

LogTransport
  └─ agent writes logs to CloudWatch Logs (same as Fargate, same log group structure)
       OR agent POSTs log chunks to the control plane's /builds/{id}/log-ingest endpoint
       (see "Log transport" section)

Reaper
  └─ terminates abandoned MicroVMs (same trigger: heartbeat expiry)
       via lambda-microvms:TerminateMicrovm instead of ecs:StopTask
```

---

## New interface: `CellLauncher`

Extract the Fargate launch path into a `CellLauncher` interface that both backends implement.
This is the "backend abstraction seam" noted as an open item in `scaleout-builder-control-plane.md`.

```java
public interface CellLauncher {
    /** Launch a cell and return an opaque handle to poll its status. */
    CellHandle launch(CellSpec spec) throws CellLaunchException;

    /** Stop a running cell immediately (cancel or reaper). */
    void stop(CellHandle handle, String reason);

    /** Poll a running cell's status. */
    CellStatus poll(CellHandle handle);
}
```

`CellHandle` is a sealed interface: `FargateHandle` (wraps `taskArn`) and `MicroVmHandle`
(wraps `microvmId` + `endpoint`). Stored as a JSON blob in `BuildRecord.CellRecord` —
the table already stores `taskArn` as a plain string; this widens it to a typed record.

**This is a DynamoDB schema change.** Existing records have a plain `taskArn` string.
New records have `{"type":"fargate","taskArn":"..."}` or `{"type":"microvm","microvmId":"...","endpoint":"..."}`.
Migration: read the old shape, write the new shape; the reaper and monitor handle both
during the transition window. No flag day needed.

---

## `MicroVmCellLauncher`

### IAM additions to the control plane role

The control plane Lambda currently holds ECS + S3 + CloudWatch + DynamoDB permissions.
MicroVM operations are a separate IAM namespace:

```
lambda-microvms:RunMicrovm
lambda-microvms:GetMicrovm
lambda-microvms:TerminateMicrovm
lambda-microvms:CreateMicrovmAuthToken
lambda-microvms:SuspendMicrovm   (Phase 2 only)
lambda-microvms:ResumeMicrovm    (Phase 2 only)
```

Plus the agent image ARN as a resource constraint on `RunMicrovm`:

```json
{
  "Effect": "Allow",
  "Action": "lambda-microvms:RunMicrovm",
  "Resource": "arn:aws:lambda:<region>:<account>:microvm-image:scaleout-build-agent"
}
```

And `lambda:PassNetworkConnector` if a VPC egress connector is attached (see below).

### `RunMicrovm` parameters

```java
RunMicrovmRequest.builder()
    .imageIdentifier(agentImageArn)          // from CDK config
    .imageVersion(agentImageVersion)         // from CDK config; same version as the image
    .executionRoleArn(microvmExecutionRoleArn)
    .idlePolicy(IdlePolicy.builder()
        .maxIdleDurationSeconds(600)         // 10 min idle → auto-terminate
        .suspendedDurationSeconds(0)         // Phase 1: no suspension
        .autoResumeEnabled(false)            // Phase 1: no resume
        .build())
    .runHookPayload(buildEnvJson)            // SCALEOUT_BUILD_ID, SCALEOUT_S3_*, etc.
    .ingressNetworkConnectors(List.of())     // no public ingress needed for builds
    .egressNetworkConnectors(List.of(vpcEgressConnectorArn))  // S3/CWL access
    .build()
```

`runHookPayload` passes the same environment variables that Fargate passes as task
environment: build ID, S3 bucket, input manifest path, output prefix, timeouts. The
agent's `/run` hook reads them via the request body.

**No ingress network connector.** The control plane does not need to reach the MicroVM
endpoint — log transport is outbound from the agent (see below). Auth tokens are
generated but only used during the `/run` hook startup sequence and health check.

### `/ready` hook

The agent must implement `/ready` (port 9000 by default, configurable). It returns 503
until the agent JVM is warm, then 200. The MicroVM platform snapshots at that point.

For Phase 1 this is the same "agent is up" signal Fargate's `RUNNING` state implies, but
explicit. The control plane does not poll `/ready` directly — the platform does it during
image build. At runtime, the `/run` hook fires after the snapshot is restored, which is
the agent's signal to start the build.

### `/run` hook

Receives the `runHookPayload` as a JSON body. The agent:
1. Reads build config from the payload
2. Downloads inputs from S3 (same as Fargate)
3. Runs `native-image` (same as Fargate)
4. Uploads artifacts to S3 (same as Fargate)
5. Returns 200 (success) or 4xx/5xx (failure) within the hook timeout

The hook timeout is `timeoutMinutes + 10` minutes (same as the Fargate `coreutils
timeout` wrapper). After the hook returns, the MicroVM is idle; the idle policy
auto-terminates it.

---

## MicroVM image build

The agent container already has everything needed: Mandrel, the agent JAR, the
entrypoint. Building a `MicrovmImage` is an additional step in the image pipeline.

### Image pipeline additions

`build-agent-image.yml` (existing workflow, builds and pushes the Docker image) gains a
new job `build-microvm-image` that runs after the Docker image is published:

```yaml
build-microvm-image:
  needs: [push-agent-image]
  runs-on: ubuntu-24.04
  steps:
    - name: Package agent as MicroVM code artifact
      run: |
        # A MicroVM code artifact is a ZIP containing a Dockerfile at the root.
        # The Dockerfile references the already-published agent image by digest.
        DIGEST=$(docker inspect --format='{{index .RepoDigests 0}}' $AGENT_IMAGE)
        cat > Dockerfile <<EOF
        FROM $DIGEST
        # /ready and /run hooks are already implemented in the agent
        EXPOSE 9000
        EOF
        zip microvm-artifact.zip Dockerfile

    - name: Upload to S3
      run: aws s3 cp microvm-artifact.zip s3://$MICROVM_ARTIFACTS_BUCKET/agent-$VERSION.zip

    - name: Create or update MicrovmImage
      run: |
        aws lambda-microvms create-microvm-image \
          --name scaleout-build-agent \
          --base-image-arn arn:aws:lambda:$REGION:aws:microvm-image:al2023-1 \
          --build-role-arn $MICROVM_BUILD_ROLE_ARN \
          --code-artifact "{\"uri\":\"s3://$MICROVM_ARTIFACTS_BUCKET/agent-$VERSION.zip\"}" \
          --hooks '{
            "microvmImageHooks": {
              "ready": {"path": "/ready", "port": 9000, "timeoutSeconds": 120},
              "validate": {"path": "/validate", "port": 9000, "timeoutSeconds": 60}
            },
            "microvmHooks": {
              "run": {"path": "/run", "port": 9000, "timeoutSeconds": '$((TIMEOUT_MINUTES + 10))'}
            }
          }'
```

**Two new IAM roles** added to the CDK stack:

- `MicrovmBuildRole` — trusted by `lambda-microvms.amazonaws.com` during image build;
  needs S3 read on the artifacts bucket and CloudWatch Logs write.
- `MicrovmExecutionRole` — assumed at runtime by the running MicroVM; needs the same
  S3 and CloudWatch permissions as the current Fargate task role. Credentials refresh
  on resume (Phase 2 only, not needed in Phase 1 where VMs terminate after each build).

### `/ready` hook implementation in the agent

The agent already has a main HTTP server (it serves the `/run` hook). Add:

```java
@GET
@Path("/ready")
public Response ready() {
    // Return 503 until warm-up is complete, 200 when ready to snapshot.
    // For Phase 1, always return 200 immediately — there is no warm state to establish.
    return Response.ok().build();
}
```

### `/validate` hook

Called after `RunMicrovm` during image build; the platform samples memory access
patterns for prefetching. Runs a synthetic build:

```java
@GET
@Path("/validate")
public Response validate() {
    // Run a trivial native-image compilation to warm the snapshot.
    // This lets the platform prefetch the memory pages accessed at startup.
    // A no-op return here would produce a cold snapshot on every RunMicrovm.
    runSyntheticBuild();
    return Response.ok().build();
}
```

---

## Log transport

Two options. The choice matters because CloudWatch Logs requires the MicroVM's execution
role to hold `logs:CreateLogStream` + `logs:PutLogEvents`, and the log group must exist
before the first write.

**Option A: CloudWatch Logs (same as Fargate)**
Agent writes logs to CloudWatch exactly as the Fargate path does. Control plane reads
via `CloudWatchLogTailer`. No protocol change.

Concern: the MicroVM needs outbound network access to CloudWatch Logs. This requires a
VPC egress connector with a route to the CloudWatch Logs VPC endpoint (or NAT). If the
VPC already has that endpoint for S3 access, there is no additional infra.

**Option B: S3 log chunks**
Agent writes log chunks to S3 (same bucket, different prefix: `builds/{id}/logs/`).
Control plane polls S3 instead of CloudWatch Logs. Removes the CloudWatch dependency
from the MicroVM's network path; adds S3 read on the control plane log path.

**Recommendation: Option A for Phase 1.** The VPC egress connector is already needed
for S3 access. Adding CloudWatch Logs to the same VPC endpoint is a one-line CDK
change. Option B is worth revisiting if CloudWatch Logs latency becomes a problem.

---

## Backend selection

Per-cell, by architecture. Config-driven:

```java
// ControlPlaneConfig.Ecs
enum CellBackend { FARGATE, MICROVM, AUTO }
Map<String, CellBackend> backendByArchitecture = Map.of(
    "arm64",  CellBackend.AUTO,   // AUTO = MICROVM if image exists, else FARGATE
    "x86_64", CellBackend.FARGATE // MicroVMs are ARM64-only
);
```

`AUTO` checks whether a MicroVM image with the configured version exists before
launching. If it does not (e.g. during the transition after deploying a new CDK stack
before the image pipeline runs), fall back to Fargate silently. This prevents a hard
failure during the rollout window.

CDK context key: `arm64Backend` = `microvm | fargate | auto`. Default: `auto`.

---

## Reaper changes

The reaper currently calls `ecs:StopTask` on cells whose heartbeat has expired.
It needs to dispatch to the right backend:

```java
CellHandle handle = CellHandle.from(cell.handleJson());
if (handle instanceof MicroVmHandle m) {
    microvmsClient.terminateMicrovm(r -> r.microvmIdentifier(m.microvmId()));
} else if (handle instanceof FargateHandle f) {
    ecsClient.stopTask(r -> r.cluster(clusterArn).task(f.taskArn()).reason(reason));
}
```

The reaper Lambda already has ECS permissions. Add `lambda-microvms:TerminateMicrovm`
to its role.

---

## CDK changes

New resources in `ScaleoutBuildControlPlaneStack`:

```typescript
// MicroVM build role (used during image build in CI, not by the Lambda at runtime)
const microvmBuildRole = new iam.Role(this, 'MicrovmBuildRole', {
    assumedBy: new iam.ServicePrincipal('lambda-microvms.amazonaws.com'),
});
artifactsBucket.grantRead(microvmBuildRole);
logGroup.grantWrite(microvmBuildRole);

// MicroVM execution role (assumed at runtime by the running MicroVM)
const microvmExecutionRole = new iam.Role(this, 'MicrovmExecutionRole', {
    assumedBy: new iam.ServicePrincipal('lambda-microvms.amazonaws.com'),
});
stagingBucket.grantReadWrite(microvmExecutionRole);
logGroup.grantWrite(microvmExecutionRole);

// Control plane role additions
controlPlaneLambda.addToRolePolicy(new iam.PolicyStatement({
    actions: [
        'lambda-microvms:RunMicrovm',
        'lambda-microvms:GetMicrovm',
        'lambda-microvms:TerminateMicrovm',
        'lambda-microvms:CreateMicrovmAuthToken',
    ],
    resources: [`arn:aws:lambda:${this.region}:${this.account}:microvm-image:scaleout-build-agent`],
}));
controlPlaneLambda.addToRolePolicy(new iam.PolicyStatement({
    actions: ['iam:PassRole'],
    resources: [microvmExecutionRole.roleArn],
    conditions: {
        StringEquals: { 'iam:PassedToService': 'lambda-microvms.amazonaws.com' },
    },
}));

// S3 bucket for MicroVM code artifacts (Dockerfile ZIPs)
const microvmArtifactsBucket = new s3.Bucket(this, 'MicrovmArtifacts', {
    removalPolicy: RemovalPolicy.RETAIN,
    encryption: s3.BucketEncryption.S3_MANAGED,
    blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
});
```

New SSM parameters (read by the control plane at cold start):

```
/scaleout-build/microvm/agent-image-arn
/scaleout-build/microvm/agent-image-version
/scaleout-build/microvm/execution-role-arn
/scaleout-build/microvm/arm64-backend        (microvm|fargate|auto)
/scaleout-build/microvm/vpc-egress-connector-arn  (optional)
```

---

## Rollout order

1. **CDK deploy** — adds MicroVM roles, S3 bucket, SSM params. `arm64Backend=fargate` initially.
   Fargate continues serving all cells. Zero downtime.
2. **Image pipeline** — `build-agent-image.yml` builds the MicroVM image. Takes ~10 minutes
   (image build + `/ready` snapshot + `/validate` warm). Pushes `scaleout-build-agent` to the
   region's MicroVM image registry.
3. **SSM update** — flip `arm64Backend` from `fargate` to `auto` (or `microvm` for hard cutover).
   Next Lambda cold start picks it up. No redeploy needed.
4. **Observe** — first few builds, compare `arm64` cell timing against Fargate baseline.
   `spotInterruptions` metric drops to zero (MicroVMs cannot be Spot-interrupted).

Rollback: flip `arm64Backend` back to `fargate` in SSM. Takes effect on the next cold start.

---

## What is not in Phase 1

**Suspend/resume for PGO.** Still needs the open questions in `microvm-build-backend.md`
answered: snapshot write/read cost at ~8 GB memory, whether `native-image` tolerates
being snapshotted mid-process, and `SUSPENDED` cell state on the wire contract.

**MicroVM for x86_64.** Platform does not support it.

**Custom idle policy per build.** The idle timeout is a deployment-time CDK parameter,
not a per-build input. The client cannot demand longer-lived VMs.

**Auth token forwarding to the client.** Auth tokens are used only by the control plane
for health checks and the `/run` hook handshake. The client never sees a MicroVM
endpoint or token.

---

## Open questions before implementation starts

1. **Regional availability.** Lambda MicroVMs availability must be confirmed for
   `eu-central-1` (our deployed region) before the CDK stack adds the resources.
   Check: `aws lambda-microvms list-managed-microvm-images --region eu-central-1`.

2. **VPC egress connector.** A VPC must already exist with a route to S3 and CloudWatch
   Logs. The current Fargate tasks run in a VPC; the MicroVMs use the same one via the
   egress connector. Confirm the VPC has the required endpoints before deploying.

3. **`/run` hook timeout ceiling.** MicroVM runtime hooks have a max timeout of 60
   seconds. `native-image` takes 5–9 minutes. The `/run` hook is therefore the wrong
   mechanism for running the build — the hook fires and must return within 60 seconds.
   **This invalidates the hook-based build execution model described above.**

   Revised approach: the `/run` hook starts the build asynchronously (forks a thread or
   process) and returns 200 immediately. The control plane then polls `/ready` or a
   `/status` endpoint on the MicroVM's endpoint URL to track completion. This requires
   the control plane to hold an auth token for the polling duration, refreshing before
   the 60-minute expiry.

   Alternatively: the agent writes build status to S3 (a `status.json` object) and the
   control plane polls S3. No auth token management, no inbound connector needed.
   **This is probably the cleaner design for Phase 1.**

4. **Image build time vs Lambda timeout.** The `CreateMicrovmImage` call is synchronous
   but image builds take ~10 minutes. The CI job must poll `GetMicrovmImage` until the
   image is `READY`, not fire-and-forget.

---

## Revised execution model (resolving question 3)

Given the 60-second hook ceiling, the build execution model is:

```
RunMicrovm
  └─ /run hook fires (≤60s limit)
       └─ agent forks native-image process
       └─ returns 200 immediately
  └─ control plane polls S3 for builds/{id}/status.json  (every 30s)
       └─ agent writes status.json on completion/failure
       └─ agent writes logs to CWL throughout
  └─ control plane reads status.json: success/failure + artifact paths
  └─ control plane calls TerminateMicrovm
```

This eliminates the need for:
- Auth tokens held by the control plane beyond the `RunMicrovm` call
- An inbound network connector to the MicroVM
- Any polling of the MicroVM API beyond `GetMicrovm` for lifecycle state

It is also closer to the Fargate model (S3 + CWL, no direct connection to the worker),
which keeps the `CellLauncher` interface thin.

The `status.json` object is a new convention, not a new protocol. Format:

```json
{
  "buildId": "01J8...",
  "cell": "NATIVE/ARM64",
  "state": "SUCCEEDED",
  "exitCode": 0,
  "artifacts": [{"path": "hello-native", "sha256": "...", "sizeBytes": 13241624}],
  "completedAt": "2026-10-03T03:00:00Z"
}
```

Written atomically (PUT then rename is not available on S3; single PUT is atomic enough
for this polling pattern since the control plane will re-read on next cycle if it sees
a partial write).
