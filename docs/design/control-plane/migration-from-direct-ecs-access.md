# Migrating from direct ECS access to the builder control plane

## Status: Proposed — design only, no code written

Companion document:
[`scaleout-builder-control-plane.md`](scaleout-builder-control-plane.md) specifies the service this
migration targets. Read that first; this document is only about how to get there.

## What is being migrated

Today the Maven plugin talks directly to ECS, S3, CloudWatch Logs, and ECR using the developer's
own AWS credentials, configured through 33 `aws-ecs.*` parameters. After migration it talks only to
the control plane's Function URL, and the developer holds no permissions on ECS, S3, CloudWatch, or
ECR at all.

The migration is not a rename. It moves a trust boundary, and it moves 22 of the 33 parameters out
of user-visible configuration entirely.

## Parameter inventory: the exact surface

Every `aws-ecs.*` parameter on `BuildMojo` today, classified. This is the authoritative list for
the migration; it was enumerated from the source, not from documentation.

### Stays client-side — build intent (11 with `aws-ecs.` properties, plus 2 without)

These describe *what to build*. The user legitimately owns them and they travel in `buildSpec`.

| Parameter | Notes |
|---|---|
| `aws-ecs.buildKinds` | validated against the server's allow-list |
| `aws-ecs.architectures` | validated against the server's allow-list |
| `aws-ecs.mainClass` | |
| `aws-ecs.imageName` | |
| `aws-ecs.nativeImageCommand` | |
| `aws-ecs.profilePath` | local path; the file is uploaded as a staged input |
| `aws-ecs.workDirectory` | purely local — where artifacts are downloaded to |
| `aws-ecs.timeoutMinutes` | |
| `aws-ecs.overallTimeoutMinutes` | clamped by server policy |
| `aws-ecs.skip` | purely local |
| `aws-ecs.forceRemote` | purely local — decides whether the service is called at all |
| `extraBuildArgs` | no `aws-ecs.` property today |
| `extraNativeImageArgs` | no `aws-ecs.` property today |

### Moves server-side — infrastructure detail (17)

These are removed from the plugin. The service owns them as deployment configuration.

| Parameter | Why it disappears |
|---|---|
| `aws-ecs.region` | implied by the endpoint URL |
| `aws-ecs.clusterArn` | service config |
| `aws-ecs.subnetIds` | service config |
| `aws-ecs.securityGroupIds` | service config |
| `aws-ecs.assignPublicIp` | service config; coupled to the VPC's NAT-less topology |
| `aws-ecs.executionRoleArn` | service config; passed via `iam:PassRole` |
| `aws-ecs.taskRoleArn` | service config; passed via `iam:PassRole` |
| `aws-ecs.logGroupName` | service config; client never reads CloudWatch directly again |
| `aws-ecs.s3Bucket` | service config; client gets presigned URLs instead |
| `aws-ecs.agentImageUri` | service config; the client should not pin the agent version |
| `aws-ecs.launchType` | service config; a backend choice, not a user choice |
| `aws-ecs.capacityProviderName` | service config |
| `aws-ecs.s3FilesFileSystemArn` | service config (mount-based staging mode) |
| `aws-ecs.s3FilesRootDirectory` | service config |
| `aws-ecs.s3FilesAccessPointArn` | service config |
| `aws-ecs.ec2HostMountPath` | service config |
| `aws-ecs.agentUsesDirectS3Io` | service config; an internal staging-mechanism detail |

`agentImageUri` deserves emphasis: today a developer can pin an arbitrary agent image, which means
they choose what code runs with the task role's permissions. Behind the service that becomes a
deployment decision, which is a security improvement independent of convenience.

### Moves server-side as policy — sizing (5)

Requestable but clamped; the applied values are echoed back. See the design document's "Resource
policy".

| Parameter | Becomes |
|---|---|
| `aws-ecs.agentCpu` | `requestedResources.cpu`, clamped |
| `aws-ecs.agentMemory` | `requestedResources.memory`, clamped |
| `aws-ecs.agentEphemeralStorageGiB` | `requestedResources.ephemeralStorageGiB`, clamped |
| `aws-ecs.maxSpotInterruptionsBeforeOnDemand` | server policy; not client-settable |
| `aws-ecs.pollIntervalSeconds` | obsolete — replaced by SSE push plus `heartbeatIntervalSeconds` |

### Added (1)

| Parameter | Notes |
|---|---|
| `aws-ecs.endpoint` | the Function URL. The only deployment-specific value a developer configures |

Net effect: **33 parameters → 13 client-side + 1 endpoint**, and `deployment.properties` shrinks
from 10 account-specific values to one URL.

## Why not a flag-day cutover

The plugin and the service must agree on a wire contract that does not exist yet, and the E2E path
through real AWS is the only thing that has ever validated this pipeline end to end. A single
switch-over would take away the working path before the replacement is proven against it. The phases
below keep the direct path working and comparable until the service path has demonstrably produced
identical artifacts.

## Phase 0 — baseline and instrumentation

No behaviour change. Establishes what "identical" means before anything moves.

1. Record a known-good baseline run of the example app on the direct path: the two artifact
   SHA-256 digests, the per-cell wall-clock durations, and the staged input digests.
2. Extract the plugin's current AWS-facing logic behind a narrow internal interface, implemented
   today by the existing direct-ECS code. This is a pure refactor with no functional change, and it
   is the seam the service client plugs into later.

### Status: done

`MatrixCell` was promoted out of `BuildMojo` to a top-level type (it is the unit of work every
backend deals in, and leaving it nested would force a service client to depend on the Mojo class
just to name its own input type). The remote path moved to
`ai.codriverlabs.scaleoutbuild.maven.backend`: `BuildBackend` (the seam), `DirectEcsBuildBackend`
(today's behaviour, lifted unchanged), and `RemoteBuildOptions` (the per-invocation knobs that are
not infrastructure identity — the existing `EcsClusterSettings` and `AgentContainerSettings` already
carry that). `BuildMojo` shrank by 151 lines and now builds the settings, constructs the backend,
and passes `this::attachArtifacts` as the artifact sink.

Verified by 99 tests green with no test changes beyond the `MatrixCell` rename, plus a real E2E run
against the live stack: both artifacts correct (`ELF x86-64` / `ELF ARM aarch64`), per-cell log
labels still cleanly separated (8/0 and 0/7), and one CAS entry for the dependency jar across three
runs.

### Correction to this document's original sketch of the seam

This document first described the interface as carrying `submit`, `status`, `logs`, `cancel`, and
`artifacts`. **That was wrong, and the implementation deliberately does not follow it.** Those five
operations are the control plane's *wire protocol*, not the plugin's seam. The direct backend has no
notion of a build id to poll or a stream to resume, because the client process *is* the supervisor;
forcing today's blocking supervision loop into a submit-then-poll shape would have been a redesign
rather than the behaviour-preserving refactor Phase 0 exists to be.

The seam is therefore a single coarse `runCells(cells, plan, buildId, artifactSink)`. A service
backend expresses submit, poll, stream, and cancel *internally*, behind that same method. Artifact
attachment stays with the caller via a sink callback, both because it needs `MavenProjectHelper` and
because passing a callback preserves today's log interleaving exactly — each cell's artifact is
attached as that cell finishes, not after all cells finish.

### Finding: artifact digests cannot be the differential-test gate

Recording the baseline produced evidence that settles an open item this document previously listed
as untested. Two runs over identical sources produced **different** artifact digests at
**identical** sizes:

| Run | `NATIVE-X86_64` | `NATIVE-ARM64` |
|---|---|---|
| 1 | `1e1e7379…` | `7de0154e…` |
| 2 | `6f0de787…` | `70f2696d…` |

The staging CAS explains it: there are **two** distinct digests for the 5060-byte example-app jar
but only **one** for the 709075-byte `commons-lang3` jar. So Maven's own jar output is not
byte-reproducible between runs — almost certainly embedded timestamps — which means the
`native-image` input differs even when the source does not, and the output digest cannot match.

Consequences, both now reflected below:

- The Phase 1 differential test **cannot** be strict about artifact digests. It uses the
  architecture, dynamic-linker path, and successful-execution comparison instead.
- Setting `project.build.outputTimestamp` in the example app would make the jar reproducible and
  could restore a strict digest gate. Worth trying, but it is a change to the example project rather
  than a property of the pipeline, so it is not assumed here.
- Unchanged dependency jars dedupe perfectly across runs and across developers; the project's own
  jar re-uploads every time. That is the expected steady state for the control plane's upload
  negotiation, and it means `POST /builds` will almost always have exactly one digest to hand back a
  presigned URL for.

Exit criteria: `mvn -B clean verify` green; a direct-path E2E run reproducing the baseline.

## Phase 1 — service deployed, direct path still default

The service exists and is usable, but nothing switches to it yet.

1. Deploy `scaleout-builder-control-plane` alongside the existing `scaleout-test-infra` stack. It
   targets the *same* ECS cluster, bucket, and roles that stack already provisions — the service is
   a new front door to existing infrastructure, not new infrastructure.
2. Add the `ServiceBuildBackend` implementation to the plugin, selected only when
   `aws-ecs.endpoint` is set. Absent that parameter, behaviour is byte-for-byte what it is today.
3. Grant the developer group the single shared policy (`lambda:InvokeFunctionUrl` +
   `lambda:InvokeFunction` on the one function). Do **not** revoke any existing ECS/S3/CloudWatch
   permission yet.

Exit criteria — the differential test, which is the real gate:

- The example app builds successfully via `aws-ecs.endpoint`.
- Both artifacts are genuine, distinct `ELF x86-64` and `ELF ARM aarch64` binaries.
- The artifact digests match the Phase 0 baseline. **Not achievable as stated** — see Phase 0's
  "artifact digests cannot be the differential-test gate". The comparison is architecture, dynamic-
  linker path, and successful execution, unless `project.build.outputTimestamp` is set on the example
  app first to make its jar reproducible.
- A reconnect across the 15-minute Lambda boundary loses no log lines, verified by comparing the
  reassembled stream against the CloudWatch stream contents directly.
- `Ctrl+C` during a service-path build results in `CANCELLED` state and stopped ECS tasks, confirmed
  via `DescribeTasks`.
- `kill -9` of the Maven JVM results in reaper-driven cancellation within the heartbeat threshold,
  also confirmed via `DescribeTasks`. This is the case the direct path handled implicitly and the
  service path must handle explicitly.

## Phase 2 — service becomes the default

1. Invert the default: the service path is used when `aws-ecs.endpoint` is set, and
   `aws-ecs.endpoint` is populated from SSM or a shared default rather than per-developer files. The
   direct path now requires an explicit opt-in flag (`aws-ecs.directEcsAccess=true`) and logs a
   deprecation warning naming the removal phase.
2. Update `docs/examples/scaleout-build-example-app` to the service path: `deployment.properties`
   drops to a single endpoint value, and the `<configuration>` block loses 22 elements.
3. Regenerate `deploy-and-capture-outputs.sh` output accordingly — it currently writes 10
   account-specific keys; it should write the endpoint.

Run in this state long enough for every developer to have completed real builds through the service.
The direct path remains as a one-flag escape hatch while the service's failure modes are still
being discovered.

## Phase 3 — remove direct access

Only once no one has needed the escape hatch.

1. Delete the direct-ECS backend implementation and the 22 parameters. This is a breaking change to
   the plugin's configuration surface and needs a major version bump.
2. **Revoke developer IAM.** Remove `ecs:RunTask`/`DescribeTasks`/`StopTask`, the staging bucket's
   S3 permissions, CloudWatch Logs read, and ECR access from developer identities. Until this step
   happens, none of the security benefit has actually been realised — everything before it is
   convenience.
3. Mark `docs/design/ci-cd/developer-test-infra-isolation.md` superseded, with a pointer to the
   control-plane documents. Its per-developer inline-policy mechanism is not needed under this model.
   Its VPC-sharing rationale and its analysis of why ECS has no resource-based policy remain
   accurate and worth keeping as the historical record of why this approach was chosen.

Exit criteria: a developer with only the shared invoke policy can build both architectures; the same
developer receives `AccessDenied` on a direct `ecs:RunTask` against the cluster.

## Rollback

Phases 1 and 2 are trivially reversible: unset `aws-ecs.endpoint`, or set
`aws-ecs.directEcsAccess=true`. Developer IAM is still intact, so the direct path works immediately.

Phase 3 is the irreversible one, in the operationally meaningful sense: re-granting revoked IAM is
easy, but the deleted backend code is not coming back without a revert. Do not start Phase 3 until
Phase 2 has run without escape-hatch usage.

The infrastructure itself is unaffected throughout. `scaleout-test-infra` remains a disposable
stack, and the service is deployed separately from it, so a service rollback never touches the
cluster or bucket.

## Repository placement

**Recommendation: keep it in this repository, as a new `scaleout-build-control-plane` module, and
split later against a stated trigger.**

Reasons to keep it here now:

- **The wire contract is built from types that already live here.** `BuildKind`, `Architecture`,
  `BuildCellRequest`, `StagingLayout`, and `ArtifactCollector` are in `scaleout-build-shared`, and
  both sides of the API need them. In one repository, a contract change that breaks the client is a
  compile error; across two, it is a runtime `400` found by whoever runs a build next.
- **The migration itself requires lockstep changes.** Every phase above touches the plugin and the
  service together. One repository means one branch, one PR, one CI run, and one reviewable diff per
  phase.
- **There is already precedent for non-published modules here.** `scaleout-test-infra` is explicitly
  "not part of the plugin's release artifact," and `scaleout-build-agent` ships as a container image
  rather than a jar. A deployed service is not a new kind of citizen in this repository.
- **House style.** `express-compute-control-plane` is an 11-module monorepo containing its own
  `infra/` CDK app alongside its services.

Reasons that will eventually argue for splitting:

- **Divergent cadence.** The plugin is published for consumers; the service is deployed. Once the
  service is deploying on its own schedule, a shared version number becomes a lie.
- **IAM review boundary.** The service role concentrates `ecs:RunTask` and `iam:PassRole`. A
  separate repository makes "who can widen the service's permissions" a repository-access question
  rather than a code-review-vigilance question.
- **Distribution surface.** The plugin is distributed under ELv2; the service never is. Keeping the
  distributed surface small and obvious has real value, though co-location does not by itself create
  a licensing problem for internal use.

**Split trigger — do it when either becomes true:** the service needs to deploy independently of a
plugin release, or the SaaS variant (EKS / eks-d-xpress / k3s-xpress) starts, at which point the
service acquires a lifecycle, threat model, and probably a team boundary that the plugin does not
share.

Splitting later is cheap *provided* `scaleout-build-shared` is treated as a real published artifact
from the start rather than a reactor-internal convenience. It already must be published, since the
plugin depends on it at compile scope — so the discipline to maintain is versioning it deliberately
and not making breaking changes to it casually, which is worth doing regardless of repository
layout.

## Open items

- Whether `aws-ecs.endpoint` is discovered via SSM (as `express-compute-control-plane` does for its
  Function URL) or configured explicitly. SSM removes the last per-developer value but adds an SSM
  read permission and a region assumption.
- Whether Phase 2's deprecation warning should escalate to a build failure before Phase 3, to force
  discovery of remaining direct-path users rather than waiting to find out.
- Whether setting `project.build.outputTimestamp` on the example app makes its jar reproducible and
  so restores a strict artifact-digest gate for the Phase 1 differential test. The jar is currently
  *not* reproducible (see Phase 0), which is why the gate is weaker than originally written.
