# Pure ECS Alternative to the Step Functions Build Matrix

This branch (`feature/pure-ecs-build-matrix`) is a direct alternative to
`feature/step-functions-build-matrix` / `docs/DESIGN.md`'s Step Functions design. It answers one
question concretely: for the specific, common case of "submit a small, fixed set of Fargate builds
(e.g. x86_64 + arm64) and stream progress," is a declarative orchestrator (Step Functions) actually
necessary, or does calling ECS directly suffice?

**Short answer: pure ECS suffices, and for a small fixed matrix it's simpler.** This document is
the tradeoff analysis; §5 says which one to actually use and when to switch.

This document covers the *compute/orchestration* axis — Step Functions vs. pure ECS, and which
ECS launch type. `docs/STAGING_ALTERNATIVES.md` covers the separate, orthogonal *staging* axis —
how build inputs and outputs actually move between the plugin and the remote builder, what's
implemented, and why every alternative considered (S3 Files, EFS, Mountpoint, `git archive`, EBS
io2+rsync, Lambda) wasn't adopted for that job. Neither document depends on the other's answer.

## 0. Three launch types, two staging mechanisms

The goal is `aws-ecs:build` (parameters `aws-ecs.*`), not `fargate:build`. This is a deliberate
choice, not cosmetic: Fargate is one *launch type* within Amazon ECS. This plugin now supports all
three ECS compute models via `aws-ecs.launchType` (`FARGATE` default, `MANAGED_INSTANCES`, `EC2`),
which split into two staging mechanisms rather than three, because of one concrete constraint
confirmed against AWS's docs:

**S3 Files volumes — the mechanism the agent's mount depends on, mounted by ECS itself into the
container — are GA on Fargate and ECS Managed Instances, but explicitly not supported on the raw
EC2 launch type:** "If you configure an S3 file system in a task definition and attempt to run it
on the Amazon EC2 launch type, the task will fail at launch." This is specifically about how the
*agent, inside the container*, reads staged files — not about `S3StagingSink`/`S3ArtifactRetriever`,
which run on the plugin's own machine as plain `S3Client` calls (`HeadObject`/`PutObject`/
`CopyObject`/`GetObject`) and are identical across every launch type; see
`docs/STAGING_ALTERNATIVES.md` §1 for exactly what those two classes do. So:

| Launch type | `aws-ecs.launchType` | Staging mechanism | Capacity |
|---|---|---|---|
| Fargate | `FARGATE` (default) | S3 Files volume, mounted by ECS itself | `FARGATE_SPOT`/`FARGATE`, AWS-managed, weighted (§2) |
| ECS Managed Instances | `MANAGED_INSTANCES` | S3 Files volume, mounted by ECS itself | Named `aws-ecs.capacityProviderName`, provisioned out of band |
| Raw EC2 | `EC2` | Host bind-mount volume, pointing at a path Mountpoint for Amazon S3 already mounted via the container instance's user-data | Named `aws-ecs.capacityProviderName`, or `launchType: EC2` directly if unset |

### ECS Managed Instances

Stages exactly like Fargate — same `s3FilesFileSystemArn`/`s3FilesRootDirectory`/
`s3FilesAccessPointArn` parameters, same `TaskDefinitionRegistrar` code path, just a different
`requiresCompatibilities` value (`MANAGED_INSTANCES`) and a different `capacityProviderStrategy`
target. What's different, and provisioned entirely out of band (this plugin never calls
`CreateCapacityProvider`): a Managed Instances capacity provider needs its own IAM infrastructure
role and an EC2 instance profile, e.g.:

```json
{
  "name": "managed-instances-cp",
  "cluster": "my-cluster",
  "managedInstancesProvider": {
    "infrastructureRoleArn": "arn:aws:iam::123456789012:role/ecsInfrastructureRole",
    "instanceLaunchTemplate": {
      "ec2InstanceProfileArn": "arn:aws:iam::123456789012:instance-profile/ecsInstanceRole",
      "networkConfiguration": { "subnets": ["subnet-..."], "securityGroups": ["sg-..."] },
      "storageConfiguration": { "storageSizeGiB": 100 }
    }
  }
}
```

Set `aws-ecs.capacityProviderName` to that provider's name. One task-definition-level constraint
worth knowing (confirmed against AWS's docs, not assumed): `ephemeralStorage` is **not** a valid
parameter for `MANAGED_INSTANCES` tasks — `TaskDefinitionRegistrar` omits it for this launch type
even if `aws-ecs.agentEphemeralStorageGiB` is set.

### Raw EC2 — Mountpoint for Amazon S3, mounted by user-data, not by the container

S3 Files being unsupported on EC2 doesn't rule out S3-backed staging on EC2 — it rules out using
*ECS's own* volume-management feature for it. Mountpoint for Amazon S3 (a standalone open source
FUSE client, independent of ECS's S3 Files feature entirely) works here, and specifically fits the
agent's actual I/O pattern: AWS's own docs state it "can list and read existing files, and it can
create new ones. It cannot modify existing files." The agent only ever reads pre-staged, immutable
inputs (`native-image.args`, jars) and writes exactly one new output file, sequentially, once —
`native-image`'s own scratch/temp traffic (the actual heavy, in-place-rewrite-prone I/O) is already
kept off the shared mount and onto local ephemeral storage, which is exactly what makes Mountpoint's
limitations irrelevant here.

**The mount is set up at the EC2 host level, by the container instance's user-data, before ECS ever
places a task on it — not inside the agent's container.** This is a deliberate choice over mounting
Mountpoint inside the container: the in-container approach needs `SYS_ADMIN`, a `/dev/fuse` device
mapping, and a custom entrypoint wrapping `AgentMain` (all real, verified requirements — see the
ECS `LinuxParameters`/`Device`/`KernelCapabilities` SDK model), none of which is needed at all once
the mount already exists on the host: the task definition just bind-mounts that host path in via a
plain `host` volume, exactly the way any other EC2-backed ECS bind mount works. Example user-data
(exact `mount-s3` flags depend on the AMI and how the container instance authenticates to S3; this
is illustrative, not copy-paste-ready):

```bash
#!/bin/bash
# Runs once at instance launch, before the ECS agent registers the instance.
mkdir -p /mnt/build
mount-s3 my-staging-bucket /mnt/build --allow-delete --uid 1001 --gid 1001
```

Set `aws-ecs.ec2HostMountPath` to wherever user-data mounted it (e.g. `/mnt/build`); the plugin
bind-mounts exactly that host path into the container at the same path the agent expects
(`AgentConfig`'s `JOBRUNR_BUILD_MOUNT_ROOT` default, also `/mnt/build`) via `TaskDefinitionRegistrar`.
**The plugin does not set up this mount itself** — same "pre-provisioned input" boundary already
established for the cluster, VPC, and IAM roles; provisioning the container instances' user-data is
the caller's responsibility, the same way provisioning the cluster itself is.

`mkdir` for the `output/` directory the agent creates before writing the binary is supported —
confirmed directly against Mountpoint's own source (`aws/mountpoint-s3`, not just the user-facing
docs): it implements a real FUSE `mkdir` handler (`MkDir`/`fs.mkdir`), and its own reference-test
harness (`mountpoint-s3-fs/tests/reftests/harness.rs`) exercises directory creation explicitly,
correctly rejecting only genuine conflicts (`EEXIST` when a file or directory already exists at
that path) rather than rejecting directory creation outright. Combined with the read/write pattern
already covered above, there is no remaining gap for the agent's actual usage.

### A fourth option, orthogonal to launch type: the agent's own direct S3 calls

`aws-ecs.agentUsesDirectS3Io` (default `false`) selects a different axis entirely from the three
launch types above — not a fourth staging *mechanism* tied to a launch type, but a way to skip
mount infrastructure altogether, on any of the three. When set, the agent downloads its staged
inputs and uploads produced artifacts itself, via plain `GetObject`/`PutObject`/`ListObjectsV2`
calls against `JOBRUNR_BUILD_S3_BUCKET` (see `cloud.plasticity.jobrunr.build.agent.S3Io`), instead
of reading/writing through the S3 Files or Mountpoint mount the launch type would otherwise need.
`BuildEnvironment`/`NativeImageBuildExecutor` are completely unaware of the difference — the agent
downloads inputs to a synthetic local "mount root" under ephemeral storage first, runs the exact
same build against it, then uploads `output/` back to the same key the plugin's
`S3ArtifactRetriever` already looks under.

This is a genuine tradeoff against the mount-based modes, not a strict improvement, and both are
kept rather than one replacing the other:

- **No mount infrastructure needed at all** — no S3 Files file system, mount targets, or
  user-data-installed Mountpoint, on any launch type, including EC2 (where S3 Files isn't an option
  regardless). Simpler to provision.
- **Every byte crosses the network twice** — once down before the build, once up after — rather
  than being read/written lazily through a FUSE-backed mount that only transfers what
  `native-image` actually touches. For a large classpath this mode downloads all of it upfront
  even if only some of it is read.
- **A genuinely new IAM requirement, now provisioned**: the task role needs its own
  `s3:GetObject`/`s3:ListBucket` (to download inputs) and `s3:PutObject` (to upload artifacts) on
  the staging bucket. `jobrunr-test-infra`'s CDK stack's task role grants the read/list pair
  already (needed for the mount-based modes' own prerequisites) plus a separate, narrowly scoped
  `s3:PutObject` statement added specifically for this mode — deliberately unconditional rather
  than gated behind a stack parameter, so the same deployed stack can test either I/O mode.
- Verified for real, end to end, against a live S3-protocol server (SeaweedFS, via Testcontainers,
  not a same-vendor emulator): `S3IoTest` and `AgentMainDirectS3IoTest` in `jobrunr-build-agent`
  cover download-preserves-structure, upload-preserves-structure, the full
  download→build→upload round trip, and that a failed build correctly skips the upload.

A bare `ecs` prefix was considered and rejected as too short/generic for a goal prefix meant to be
unambiguous in a `pom.xml` or CLI transcript read out of context — `aws-ecs` was chosen instead.

## 1. Progress streaming was never a Step Functions feature

Both designs stream build progress the same way: the task's `awslogs` log driver ships stdout/
stderr to CloudWatch Logs, and `CloudWatchLogTailer` polls `FilterLogEvents` incrementally against
a known `logStreamNamePrefix`. This is unchanged, byte-for-byte, between the two branches — whoever
calls `RunTask` (the plugin directly, or a Step Functions `RunTask.sync` state) is irrelevant to how
logs get streamed back. Dropping Step Functions costs nothing here.

## 2. What pure ECS puts back on the plugin

Three things Step Functions's ASL expressed declaratively become code again:

1. **Launch.** `EcsTaskLauncher.runTask` — a direct `RunTask` call, with a `capacityProviderStrategy`
   or `launchType` depending on `aws-ecs.launchType` (§0), task overrides carrying the cell's
   environment variables. For `FARGATE`, this prefers `FARGATE_SPOT` with an on-demand `FARGATE`
   fallback expressed as capacity provider `base`/`weight` rather than separate retry logic — ECS
   itself falls back to on-demand capacity when Spot capacity is unavailable for the higher-weighted
   strategy item. `MANAGED_INSTANCES`/`EC2` target a single named capacity provider instead (or
   `launchType: EC2` directly for EC2 with none configured) — there is no AWS-managed Spot/
   on-demand pair to weight between for those, since Spot vs. on-demand is a property of the named
   capacity provider's own configuration, provisioned out of band.
2. **Fan-out across cells.** With no Map state doing this for us, `BuildMojo` launches and
   supervises every remote cell on its own thread from a fixed-size `ExecutorService` sized to the
   number of remote cells — bounded concurrency without needing a `MaxConcurrency` setting, since
   this plugin never launches more tasks in one invocation than that.
3. **Relaunch on Spot interruption.** `EcsTaskSupervisor` polls `DescribeTasks` and relaunches
   when a stop looks like a genuine Spot reclaim — for `FARGATE` only, see §3.

By default, a cell whose target architecture matches the machine running `mvn` builds locally with
no AWS calls at all (`BuildMojo.splitLocalAndRemote`) — only the non-matching architecture's cell
goes remote. Set `aws-ecs.forceRemote=true` to send every non-JVM cell to ECS regardless of host
match, e.g. to keep the local toolchain out of the loop entirely or to exercise the remote path for
an architecture that happens to match the host.

## 3. Spot interruption detection is a real, verified gap — and Fargate-only today

This is the part worth being precise about rather than assuming. ECS's `stopCode` field is a
**strict enum** (confirmed against the SDK model) of exactly three values:
`TaskFailedToStart`, `EssentialContainerExited`, `UserInitiated`. **There is no dedicated stop code
for a Spot reclaim.**

AWS's documented signal instead is the free-text `stoppedReason` field. For a genuine Fargate Spot
interruption, its value is specifically the string `"Your Spot Task was interrupted."` — confirmed
against AWS's own troubleshooting guidance and support threads, not guessed. `EcsTaskSupervisor`
matches this string case-sensitively and verbatim, deliberately not with a loose substring check,
so an unrelated `stoppedReason` that happens to share words is never mistaken for an interruption
(see `EcsTaskSupervisorTest#doesNotTreatAnUnrelatedStoppedReasonAsASpotInterruption`).

**This detection is scoped to `FARGATE` only.** The exact `stoppedReason` wording ECS uses for a
`MANAGED_INSTANCES`/`EC2` Spot reclaim has not been verified against AWS's docs — reusing the
Fargate-Spot string for those launch types would either silently never match (an EC2/Managed
Instances Spot reclaim treated as an ordinary terminal failure instead of retried) or, worse, be
presented as verified when it is not. `EcsTaskSupervisorTest#managedInstancesNeverTreatsAStopAsA
SpotInterruptionEvenWithTheFargateSpotWording` is a regression guard for exactly this. Automatic
Spot-interruption retry for `MANAGED_INSTANCES`/`EC2` is a real follow-up, not yet implemented —
find and verify the correct `stoppedReason` (or other signal) for those launch types before adding
it.

This is strictly less robust than Step Functions' `Retry`/`ErrorEquals`, which matches on typed,
stable error names Step Functions itself defines. A wording change on AWS's side to this specific
message would silently break pure-ECS interruption detection with no compile-time or deploy-time
warning — it would just stop relaunching on Spot reclaims and start reporting them as build
failures instead. Also worth knowing: a stopped task's details, including `stoppedReason`, are only
queryable via `DescribeTasks` for **one hour** after the task stops — not a concern for
`EcsTaskSupervisor`'s own tight poll loop (which reads the detail immediately), but relevant if
this detection logic is ever reused against an already-stopped task discovered later.

## 4. What each approach costs and buys

| | Step Functions | Pure ECS |
|---|---|---|
| Infrastructure to deploy | State machine + its execution role | None beyond what ECS already needs |
| IAM surface | `states:StartExecution`/`DescribeExecution` on the plugin; `ecs:RunTask`/`StopTask`/`iam:PassRole` on the state machine's *own* role | `ecs:RunTask`/`StopTask`/`DescribeTasks` directly on the plugin's principal |
| Fan-out over N cells | Declarative (`Map` state, `MaxConcurrency`) | Explicit thread pool in `BuildMojo` |
| Spot interruption detection | Typed `Retry`/`ErrorEquals` | Free-text `stoppedReason` string match, Fargate-only today (§3) |
| Execution history | 90 days, queryable via `DescribeExecution`/`GetExecutionHistory` | Whatever CloudWatch Logs retention is configured; ECS's own stopped-task detail expires after 1 hour |
| Scaling to a larger matrix (JVM/PGO variants) | Free — same `Map` state, larger `cells` array | Every additional cell is still just another thread in the pool; no *new* code needed, but no built-in aggregation/tolerance semantics either |
| Code to maintain | `BuildMatrixStateMachineDefinition` (ASL), `StateMachineManager`, `StepFunctionsExecutionSupervisor` | `EcsTaskLauncher`, `EcsTaskSupervisor` |

## 5. When to use which

**Use pure ECS (this branch)** when the matrix is small and fixed — the concrete case that
motivated this branch, "x86_64 + arm64, plain native builds" — and you'd rather not deploy or
version a state machine for it. This is the simpler, more direct choice for that case.

**Use Step Functions (`feature/step-functions-build-matrix`)** if the full matrix from
`docs/DESIGN.md` §3 comes back into scope — `JVM`/`NATIVE`/`NATIVE_PGO_INSTRUMENT`/
`NATIVE_PGO_OPTIMIZE` × architecture, up to 7 cells — or if the typed `Retry`/`Catch` semantics and
90-day execution history matter more than the extra infrastructure. Nothing about the matrix
computation, staging (`S3StagingSink`/`S3ArtifactRetriever`), task definition registration
(`TaskDefinitionRegistrar`), or the agent (`AgentMain`) differs between the two branches — only the
orchestration layer above `RunTask` changes. (The Step Functions branch has not yet been updated
with the `MANAGED_INSTANCES`/`EC2` launch-type support described in §0 — it currently only targets
Fargate.)

## 6. Not yet validated against live AWS

Same caveat as both prior designs: no ECS cluster, VPC, S3 Files filesystem, EC2 container
instances, or Mountpoint-for-S3 user-data mount has been provisioned. `EcsTaskSupervisor`'s Spot-
interruption detection in particular has only been exercised against a mocked `stoppedReason`
string matching AWS's documentation — it has not been observed against a real Spot reclaim.
Mountpoint's own `mkdir` support is confirmed against its source (§0), but the EC2 launch type as a
whole — the user-data mount, the host bind mount, the container instance IAM/network setup — is
still unexercised against real infrastructure.
