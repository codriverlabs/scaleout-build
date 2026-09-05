# jobrunr-test-infra

AWS CDK (Java) app that provisions a minimal, disposable AWS environment for exercising
`aws-ecs:build` against real AWS — specifically the `FARGATE` launch type with S3 Files staging
(see `docs/PURE_ECS_ALTERNATIVE.md` §0). This is a test harness, not a production reference
architecture, and not part of the plugin's release artifact.

## What it creates

- A VPC with public subnets only, no NAT gateway (keeps cost near zero; requires
  `aws-ecs.assignPublicIp=true` on the plugin side), DNS hostnames/support explicitly enabled
  (S3 Files mounting fails DNS resolution without both — a real, distinct failure mode, not a
  formality).
- An S3 bucket with **versioning enabled** — required, not optional: S3 Files relies on object
  versions for consistency.
- An S3 Files file system associated with that bucket, with its own service role (trust policy and
  permissions copied directly from AWS's `CfnFileSystem` CDK documentation example — the
  `elasticfilesystem.amazonaws.com` service principal is correct as written, not a typo).
- One S3 Files mount target per Availability Zone (S3 Files needs an explicit mount target per AZ —
  it is not automatically reachable just because the file system exists).
- A security group for the mount targets allowing inbound TCP **2049** (confirmed as the actual
  port S3 Files mounting uses — connection timeouts on this port are "the #1 mount failure" per
  AWS's own troubleshooting guidance) from the Fargate task security group only.
- An ECS cluster (Container Insights enabled) and an ECR repository for the agent image.
- A CloudWatch Logs group (`/jobrunr/build-agent`, 1 week retention).
- An ECS task execution role (`AmazonECSTaskExecutionRolePolicy`) and a task role with the exact
  two-part policy AWS's "Prerequisites for S3 Files" documentation specifies:
  `AmazonS3FilesClientFullAccess` (mount + read + write against the file system) plus a separate
  scoped inline policy granting `s3:GetObject`/`s3:GetObjectVersion`/`s3:ListBucket` directly on the
  staging bucket. The task role does **not** get broader S3 write access to the bucket — the
  plugin's own credentials do the direct S3 staging/retrieval (`S3StagingSink`/
  `S3ArtifactRetriever`); the task only ever reads/writes through the S3 Files mount.

Every resource is tagged for easy teardown (`RemovalPolicy.DESTROY`, `autoDeleteObjects`/
`emptyOnDelete` on the bucket/repository) — this stack is meant to be deployed, used for one or a
few test runs, and destroyed, not left running.

## Deploying

```bash
# From the repo root, or from this directory directly.
cd jobrunr-test-infra
mvn -q compile
cdk bootstrap   # once per account/region, if not already bootstrapped
cdk deploy
```

`cdk.json` wires the CDK CLI to run the compiled Java app via `mvn ... exec:java` — no separate
build step beyond `mvn compile` is required before `cdk synth`/`cdk deploy`.

Deploy takes a few minutes; most of that is the S3 Files file system and its mount targets. If the
S3 Files file system gets stuck in `creating` status, that's almost always an IAM issue on its
*service* role (not the task role) — check with `aws s3files get-file-system --file-system-id
<id>` and read `statusMessage`; S3 Files does not validate that role's permissions until creation
time. The account also needs S3 Files GA availability in the target region — this hasn't been
independently checked for every region here, and the stack cannot self-verify it before deploying.

## Feeding the outputs into `aws-ecs:build`

`cdk deploy` prints every output the plugin needs directly:

| CDK output | `aws-ecs.*` parameter |
|---|---|
| `ClusterArn` | `clusterArn` |
| `SubnetIds` (comma-separated) | `subnetIds` |
| `SecurityGroupId` | `securityGroupIds` |
| `ExecutionRoleArn` | `executionRoleArn` |
| `TaskRoleArn` | `taskRoleArn` |
| `S3FilesFileSystemArn` | `s3FilesFileSystemArn` |
| `LogGroupName` | `logGroupName` |
| `S3Bucket` | `s3Bucket` |
| `Region` | `region` |
| `AgentRepositoryUri` | (used to build/push the agent image; not a plugin parameter itself) |

Also set `aws-ecs.assignPublicIp=true` and `aws-ecs.launchType=FARGATE` (the default) — this stack
only creates public subnets, so the task needs a public IP to reach ECR/CloudWatch/the S3 Files
mount target.

Before the agent image exists anywhere pullable, build and push it:

```bash
aws ecr get-login-password --region <region> | docker login --username AWS --password-stdin <AgentRepositoryUri, minus the trailing /jobrunr-build-agent>
docker build -t <AgentRepositoryUri>:latest jobrunr-build-agent/
docker push <AgentRepositoryUri>:latest
```

Then set `aws-ecs.agentImageUri=<AgentRepositoryUri>:latest`.

## Destroying

```bash
cdk destroy
```

Everything is set to `RemovalPolicy.DESTROY` with auto-delete/empty-on-delete where applicable
(bucket objects, ECR images), so `cdk destroy` should leave nothing behind — this is a genuinely
disposable stack, not one that needs manual cleanup passes afterward.

## What this does not cover

- The `MANAGED_INSTANCES` and `EC2` launch types (see `docs/PURE_ECS_ALTERNATIVE.md` §0) are not
  provisioned by this stack — `MANAGED_INSTANCES` needs its own capacity provider with an
  infrastructure role and EC2 instance profile; `EC2` needs container instances with Mountpoint for
  Amazon S3 already mounted via user-data. Both are real follow-up work, not yet attempted here.
- Spot capacity behavior, real Spot interruptions, and `EcsTaskSupervisor`'s relaunch logic are
  exercised by this environment but not validated by it automatically — that still requires
  actually running `aws-ecs:build` against it and observing the result.
