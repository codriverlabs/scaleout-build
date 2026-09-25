# Migration from direct ECS access to the builder control plane

## Status: complete

Executed across PRs #12–#14, #19–#23, #30, #31 and #32. The control plane is deployed, the direct-ECS
path is deleted, and an end-to-end build has been verified against real AWS. See
[`HANDOVER.md`](HANDOVER.md) for current deployment state and any remaining operational work.

This document is a **record of what the migration did**, kept for the reasoning that is still useful:
the parameter inventory, the reproducibility finding, and the repository-placement argument with its
split trigger.

It previously described a phased plan that retained the direct path behind a flag through Phases 1–2,
with a differential test comparing both paths. **That plan was not executed** and the phase-by-phase
narrative has been removed rather than left to be read as current. What happened instead is below.

Companion documents: [`scaleout-builder-control-plane.md`](scaleout-builder-control-plane.md) specifies
the service; [`storage-layout-and-isolation.md`](storage-layout-and-isolation.md) owns the storage
layout and supersedes any assumption here of a single flat content-addressed store.

## What changed

Before: the Maven plugin talked directly to ECS, S3, CloudWatch Logs and ECR using the developer's own
AWS credentials, configured through 33 `aws-ecs.*` properties.

After: it talks only to the control plane's Function URL. A caller needs `lambda:InvokeFunctionUrl` and
`lambda:InvokeFunction` on one function, and no permissions on ECS, S3, CloudWatch or ECR at all.

This was not a rename. It moved a trust boundary, and it removed 19 parameters from user-visible
configuration entirely.

**The security benefit lands when developer IAM is revoked, not when the code merges.** Until the old
policies are removed, every developer still *can* reach ECS and S3 directly; the plugin simply no longer
does it for them. That revocation is the step that makes the rest of this real, and it is deliberately
called out because it is easy to treat the merge as the finish line.

## Parameter inventory

Enumerated from source, not documentation. This is the authoritative record of the surface change.

**33 `aws-ecs.*` properties (plus 2 parameters with no property) → 15 `scaleout-build.*` properties
(plus the same 2).** The prefix changed with the surface, including the goal, which is now
`scaleout-build:build`; the work directory is `target/scaleout-build`.

### Kept — build intent (11 with properties, plus 2 without)

These describe *what to build*. The user legitimately owns them, and they travel in `buildSpec`.

| Parameter | Notes |
|---|---|
| `scaleout-build.buildKinds` | validated against the server's allow-list |
| `scaleout-build.architectures` | validated against the server's allow-list |
| `scaleout-build.mainClass` | |
| `scaleout-build.imageName` | |
| `scaleout-build.nativeImageCommand` | |
| `scaleout-build.profilePath` | local path; the file is uploaded as a staged input |
| `scaleout-build.workDirectory` | purely local — where artifacts are downloaded to |
| `scaleout-build.timeoutMinutes` | |
| `scaleout-build.overallTimeoutMinutes` | clamped by server policy |
| `scaleout-build.skip` | purely local |
| `scaleout-build.forceRemote` | purely local — decides whether the service is called at all |
| `extraBuildArgs` | no property, then or now |
| `extraNativeImageArgs` | no property, then or now |

### Deleted — infrastructure detail (17)

Removed from the plugin outright. The service owns them as deployment configuration.

| Parameter | Why it disappeared |
|---|---|
| `aws-ecs.region` | implied by the endpoint URL |
| `aws-ecs.clusterArn` | service config |
| `aws-ecs.subnetIds` | service config |
| `aws-ecs.securityGroupIds` | service config |
| `aws-ecs.assignPublicIp` | service config; coupled to the VPC's NAT-less topology |
| `aws-ecs.executionRoleArn` | service config; passed via `iam:PassRole` |
| `aws-ecs.taskRoleArn` | service config; passed via `iam:PassRole` |
| `aws-ecs.logGroupName` | service config; the client never reads CloudWatch directly again |
| `aws-ecs.s3Bucket` | service config; the client gets presigned URLs instead |
| `aws-ecs.agentImageUri` | service config; the client should not pin the agent version |
| `aws-ecs.launchType` | service config; a backend choice, not a user choice |
| `aws-ecs.capacityProviderName` | service config |
| `aws-ecs.s3FilesFileSystemArn` | service config (mount-based staging mode) |
| `aws-ecs.s3FilesRootDirectory` | service config |
| `aws-ecs.s3FilesAccessPointArn` | service config |
| `aws-ecs.ec2HostMountPath` | service config |
| `aws-ecs.agentUsesDirectS3Io` | service config; an internal staging-mechanism detail |

`agentImageUri` deserves emphasis. A developer who can pin an arbitrary agent image chooses what code
runs with the task role's permissions. Behind the service that is a deployment decision — a security
improvement independent of convenience, and the single strongest argument for deleting rather than
deprecating.

### Kept as clamped requests — sizing (3)

**This is where the original plan and the outcome differ.** These were classified as "moves server-side
as policy", implying they left the client. They did not: they remain client-side as *requests*, renamed
so the name says so, and the server clamps them and echoes the applied values back. A clamp is therefore
visible in the build log rather than silent.

| Before | After |
|---|---|
| `aws-ecs.agentCpu` | `scaleout-build.requestedCpu` → `requestedResources.cpu`, clamped |
| `aws-ecs.agentMemory` | `scaleout-build.requestedMemory` → `requestedResources.memory`, clamped |
| `aws-ecs.agentEphemeralStorageGiB` | `scaleout-build.requestedEphemeralStorageGiB` → clamped |

### Deleted — obsolete (2)

Also originally grouped under sizing, but neither survived in any form.

| Parameter | Why |
|---|---|
| `aws-ecs.maxSpotInterruptionsBeforeOnDemand` | server policy; not client-settable |
| `aws-ecs.pollIntervalSeconds` | obsolete — replaced by SSE push plus a client heartbeat |

### Added (1)

| Parameter | Notes |
|---|---|
| `scaleout-build.endpoint` | the Function URL, and the only deployment-specific value a developer sets |

Required, with no default. Not a region either: the host is `<id>.lambda-url.<region>.on.aws`, so the
client parses the SigV4 signing region out of the endpoint. `deployment.properties` in the example app
went from ten account-specific values to this one line.

The endpoint is also published to SSM at `/scaleout-build/control-plane/endpoint` by the CDK stack, so a
developer can read it back without being told. That resolves an open item this document used to carry as
an either/or: it is both, because the SSM parameter is a convenience for discovery while the plugin
property stays explicit and needs no SSM read permission.

## Why the direct path was deleted rather than deprecated

A deprecation period buys a safety net at the cost of keeping two code paths, two sets of AWS
permissions, and a differential test alive. It was rejected because:

- **The escape hatch is the vulnerability.** A `directEcsAccess` flag means developer IAM must stay
  granted, which means the security benefit never lands. The thing being migrated away from has to stop
  being reachable for the migration to mean anything.
- **There was nothing to compare against.** The differential test was the main argument for retention,
  and artifact digests turned out to be unusable as its gate (below), which left it comparing
  architecture and execution — exactly what a single-path verification already does.
- **The predecessor stack could not coexist.** `scaleout-test-infra` declared the same ECR repository
  and CloudWatch log group names as the control-plane stack, so it had to be destroyed before the new
  stack could deploy. Retention was not actually available without renaming resources in a stack that
  was about to be deleted anyway.

The cost is a real breaking change: consumers must set `scaleout-build.endpoint` and rename every
property. That is a major version bump, which is the honest signal.

## Finding: artifact digests cannot be a verification gate

Recording the baseline produced evidence that settles what was previously an untested assumption. Two
runs over identical sources produced **different** artifact digests at **identical** sizes:

| Run | `NATIVE-X86_64` | `NATIVE-ARM64` |
|---|---|---|
| 1 | `1e1e7379…` | `7de0154e…` |
| 2 | `6f0de787…` | `70f2696d…` |

The staging CAS explains it: there were **two** distinct digests for the 5060-byte example-app jar but
only **one** for the 709075-byte `commons-lang3` jar. Maven's own jar output is not byte-reproducible
between runs — almost certainly embedded timestamps — so the `native-image` input differs even when the
source does not, and the output digest cannot match.

Consequences:

- Verification uses architecture, dynamic-linker path, plausible size, and successful execution. Not
  digests.
- Unchanged dependency jars dedupe perfectly across runs and across developers; the project's own jar
  re-uploads every time. That is the expected steady state, and it is why the CAS must be keyed by
  **blob** digest rather than by a project digest — a project digest changes every build, so every build
  would re-upload the entire dependency set.
- Setting `project.build.outputTimestamp` on the example app would make its jar reproducible and could
  restore a strict digest gate. Still untried; see open items.

## What was verified

The end-to-end run through the control plane produced:

```
NATIVE-X86_64/hello-native   ELF 64-bit LSB executable, x86-64        13372680 bytes
NATIVE-ARM64/hello-native    ELF 64-bit LSB executable, ARM aarch64   13241624 bytes
```

Both byte sizes match the pre-migration direct-path baseline exactly, which given the digest finding
above is the strongest available evidence that routing through the service did not change the output.

Also confirmed: a re-run reported `0 input(s) to upload, 3 already staged`, so per-owner
content-addressed staging dedupes; and per-cell log labels stayed separated, so the cross-labelling bug
fixed in `7782771` did not return when log streaming moved behind SSE.

Ten defects were found by deploying and running, none of which the 122 unit tests then passing could
have caught, because not one of them started the application. `ci.yml` and the Quarkus boot tests now
close that specific gap.

## Rollback

**There is no direct path to fall back to.** This section previously said to unset the endpoint or set
`directEcsAccess=true`, with developer IAM still intact — all of which is now false and would waste time
during an incident.

What rollback actually means:

- **Client side:** pin the previous major version of the plugin. The direct-ECS backend exists only in
  history, so a code-level rollback is a revert, not a flag.
- **Server side:** redeploy the previous service version. `scripts/deploy-local.sh` and the deploy
  workflow both take a mode, and the CDK stack is the only thing that needs to change, so roll-forward
  is usually faster than reverting.
- **State is safe either way.** The staging bucket and the DynamoDB table are `RemovalPolicy.RETAIN` and
  survive `cdk destroy`, so tearing the stack down does not lose staged content or build records.

Realistically the recovery path is roll-forward: fix the service and redeploy. That is a consequence of
deleting the escape hatch, and it was the accepted trade.

## Repository placement

**Decision: kept in this repository, split later against a stated trigger.** Still the current state —
eight modules, of which four are published and four are deployed or shipped as an image.

Reasons it stayed:

- **The wire contract is built from types that live here.** `BuildKind`, `Architecture` and
  `StagingLayout` are in `scaleout-build-shared`, and both sides of the API need them. In one repository
  a contract change that breaks the client is a compile error; across two it is a runtime `400` found by
  whoever runs a build next. This proved out in practice — the `AgentEnvironment`/`AgentConfig` variables
  are matched by *name* with no compile-time link, and that pairing is precisely where drift did occur
  (commit `b33f9e2`, 14 variables carrying a stale prefix).
- **The migration required lockstep changes.** Every step touched the plugin and the service together:
  one branch, one CI run, one reviewable diff.
- **Non-published modules were already precedent.** `scaleout-build-agent` ships as a container image
  rather than a jar, and the CDK module is explicitly not part of the release artifact.
- **House style.** `express-compute-control-plane` is a monorepo containing its own CDK app alongside its
  services.

Reasons that will eventually argue for splitting:

- **Divergent cadence.** The plugin is published for consumers; the service is deployed. Once the service
  deploys on its own schedule, a shared version number becomes a lie. `publish.yml` narrows this by
  publishing only the four consumable modules, but it does not remove it.
- **IAM review boundary.** The service role concentrates `ecs:RunTask` and `iam:PassRole`. A separate
  repository makes "who can widen the service's permissions" a repository-access question rather than a
  code-review-vigilance question.
- **Distribution surface.** The plugin is distributed under ELv2; the service never is. Keeping the
  distributed surface small and obvious has value, though co-location does not by itself create a
  licensing problem for internal use.

**Split trigger — when either becomes true:** the service needs to deploy independently of a plugin
release, or the SaaS variant starts, at which point the service acquires a lifecycle and threat model the
plugin does not share.

Splitting stays cheap *provided* `scaleout-build-shared` is treated as a real published artifact rather
than a reactor-internal convenience. `publish.yml` now publishes it deliberately, alongside the root POM
it inherits from — so the discipline to maintain is versioning it carefully and not making casual
breaking changes, which is worth doing regardless of layout.

## Open items

- Whether setting `project.build.outputTimestamp` on the example app makes its jar reproducible and so
  restores a strict artifact-digest gate. It is a change to the example project rather than to the
  pipeline, so it was not assumed here.
- Whether developer IAM has actually been revoked in the target account. The code no longer needs it; see
  the note under "What changed" for why this is the step that matters.
