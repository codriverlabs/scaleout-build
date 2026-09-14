# scaleout-test-infra

AWS CDK (Java) app that provisions a minimal, disposable AWS environment for exercising
`aws-ecs:build` against real AWS. This is a test harness, not a production reference architecture,
and not part of the plugin's release artifact.

Two staging mechanisms, selected by the `includeS3Files` CDK context key (default **`false`**):

- **`includeS3Files=false` (default)** — plain S3 with content-hash dedup, the settled staging
  design (see `docs/STAGING_ALTERNATIVES.md`). No mount infrastructure is provisioned at all. Pair
  this with `aws-ecs.agentUsesDirectS3Io=true` so the agent's own reads/writes go straight to S3
  too, rather than through a mount — this is the mode a new deployment of this stack should use.
- **`includeS3Files=true`** — the older, mount-based mechanism: an S3 Files file system mounted by
  ECS itself into the container, used by `aws-ecs.launchType=FARGATE`/`MANAGED_INSTANCES` when
  `aws-ecs.agentUsesDirectS3Io` is left at its default (`false`). See `docs/PURE_ECS_ALTERNATIVE.md`
  §0 for why this mechanism exists at all and its tradeoffs against plain S3.

## What it always creates, regardless of `includeS3Files`

- A VPC with public subnets only, no NAT gateway (keeps cost near zero; requires
  `aws-ecs.assignPublicIp=true` on the plugin side), DNS hostnames/support explicitly enabled.
- An S3 bucket with **versioning enabled**.
- An ECS cluster (Container Insights enabled) and an ECR repository for the agent image.
- A CloudWatch Logs group (`/scaleout-build/build-agent`, 1 week retention).
- An ECS task execution role (`AmazonECSTaskExecutionRolePolicy`) and a task role granting
  `s3:GetObject`/`s3:GetObjectVersion`/`s3:ListBucket` on the staging bucket (needed either way —
  the plugin's own `S3StagingSink`/`S3ArtifactRetriever` never depend on S3 Files, and the agent's
  direct-S3-calls mode needs its own read access too) plus `s3:PutObject` on the bucket's objects
  (needed for `aws-ecs.agentUsesDirectS3Io=true`, where the agent uploads produced artifacts
  itself under this role's own credentials rather than through a mount).

## What `includeS3Files=true` additionally creates

- DNS hostnames/support were already enabled above, but they specifically matter for this mode:
  S3 Files mounting fails DNS resolution without both — a real, distinct failure mode, not a
  formality.
- An S3 Files file system associated with the staging bucket, with its own service role (trust
  policy and permissions copied directly from AWS's `CfnFileSystem` CDK documentation example —
  the `elasticfilesystem.amazonaws.com` service principal is correct as written, not a typo).
- One S3 Files mount target per Availability Zone (S3 Files needs an explicit mount target per AZ —
  it is not automatically reachable just because the file system exists).
- A security group for the mount targets allowing inbound TCP **2049** (confirmed as the actual
  port S3 Files mounting uses — connection timeouts on this port are "the #1 mount failure" per
  AWS's own troubleshooting guidance) from the Fargate task security group only.
- The task role additionally gets the `AmazonS3FilesClientFullAccess` managed policy (mount + read
  + write against the file system), matching AWS's "Prerequisites for S3 Files" documentation
  exactly.

Every resource is tagged for easy teardown (`RemovalPolicy.DESTROY`, `autoDeleteObjects`/
`emptyOnDelete` on the bucket/repository) — this stack is meant to be deployed, used for one or a
few test runs, and destroyed, not left running.

## Deploying

The fastest path — deploys the stack and writes `deployment.properties` for
[`docs/examples/scaleout-build-example-app`](../docs/examples/scaleout-build-example-app) directly from the real
CloudFormation outputs, rather than transcribing `cdk deploy`'s console output by hand:

```bash
cd scaleout-test-infra
AWS_REGION=eu-west-1 ./deploy-and-capture-outputs.sh          # plain S3 (default)
AWS_REGION=eu-west-1 ./deploy-and-capture-outputs.sh --include-s3-files  # mount-based mechanism instead
```

See `./deploy-and-capture-outputs.sh --help` for `--yes` (skip the interactive approval prompt,
for CI), `--output <path>` (write elsewhere), and `--skip-deploy` (just re-capture outputs from an
already-deployed stack, without deploying again). It reads stack outputs via
`aws cloudformation describe-stacks`, not by parsing `cdk deploy`'s console output, and maps each
one to the exact `deployment.properties` key the example app's `pom.xml` expects — see "Feeding
the outputs into `aws-ecs:build`" below for that same mapping if you'd rather do it by hand, or are
feeding the outputs into a different project's `pom.xml`.

Or the plain CDK CLI directly, without output capture:

```bash
# From the repo root, or from this directory directly.
cd scaleout-test-infra
mvn -q compile
cdk bootstrap   # once per account/region, if not already bootstrapped

# Plain S3 (default, recommended for new deployments):
cdk deploy

# Or explicitly, to deploy the older mount-based mechanism instead:
cdk deploy -c includeS3Files=true
```

`cdk.json` wires the CDK CLI to run the compiled Java app via `mvn ... exec:java` — no separate
build step beyond `mvn compile` is required before `cdk synth`/`cdk deploy`. To target a specific
region (e.g. `eu-west-1`), export **`AWS_REGION=eu-west-1`** before running `cdk bootstrap`/
`cdk deploy` — not `CDK_DEFAULT_REGION` directly: the CDK CLI computes its own
`CDK_DEFAULT_ACCOUNT`/`CDK_DEFAULT_REGION` from the currently active AWS credentials/profile (via
`AWS_REGION`/`AWS_DEFAULT_REGION`/the profile's configured region) and always injects its own
resolved values into the app subprocess, overriding anything set directly in the parent shell —
confirmed by hitting this directly: setting `CDK_DEFAULT_REGION` on the `cdk` invocation itself had
no effect, while `AWS_REGION` did.

With `includeS3Files=true`, deploy takes a few minutes longer than the default — most of that
extra time is the S3 Files file system and its mount targets. If the S3 Files file system gets
stuck in `creating` status, that's almost always an IAM issue on its *service* role (not the task
role) — check with `aws s3files get-file-system --file-system-id <id>` and read `statusMessage`;
S3 Files does not validate that role's permissions until creation time. The account also needs S3
Files GA availability in the target region — this hasn't been independently checked for every
region here, and the stack cannot self-verify it before deploying (irrelevant for the default,
plain-S3 mode).

## Feeding the outputs into `aws-ecs:build`

`cdk deploy` prints every output the plugin needs directly (or use `deploy-and-capture-outputs.sh`
above to skip this table entirely and get a ready-to-use `deployment.properties` automatically):

| CDK output | `aws-ecs.*` parameter |
|---|---|
| `ClusterArn` | `clusterArn` |
| `SubnetIds` (comma-separated) | `subnetIds` |
| `SecurityGroupId` | `securityGroupIds` |
| `ExecutionRoleArn` | `executionRoleArn` |
| `TaskRoleArn` | `taskRoleArn` |
| `S3FilesFileSystemArn` (only emitted with `includeS3Files=true`) | `s3FilesFileSystemArn` |
| `LogGroupName` | `logGroupName` |
| `S3Bucket` | `s3Bucket` |
| `Region` | `region` |
| `AgentRepositoryUri` | (used to build/push the agent image; not a plugin parameter itself) |

For the default (`includeS3Files=false`) deployment, also set `aws-ecs.agentUsesDirectS3Io=true`
so the agent skips mount-based I/O entirely. Either way, also set `aws-ecs.assignPublicIp=true`
and `aws-ecs.launchType=FARGATE` (the default) — this stack only creates public subnets, so the
task needs a public IP to reach ECR/CloudWatch/S3.

Before the agent image exists anywhere pullable, build and push it — `build-local.sh` at the repo
root does this directly (single-arch `--load`, or `--push`/`--multi-arch` against ECR):

```bash
./build-local.sh --multi-arch <AgentRepositoryUri>
```

Then set `aws-ecs.agentImageUri=<AgentRepositoryUri>:latest`.

## Destroying

```bash
cdk destroy
```

If you deployed with `-c includeS3Files=true`, pass the same context flag to `cdk destroy` too —
CDK context must match between deploy and destroy for the CLI to resolve the same stack
configuration. Everything is set to `RemovalPolicy.DESTROY` with auto-delete/empty-on-delete where
applicable (bucket objects, ECR images), so `cdk destroy` should leave nothing behind — this is a
genuinely disposable stack, not one that needs manual cleanup passes afterward.

## What this does not cover

- The `MANAGED_INSTANCES` and `EC2` launch types (see `docs/PURE_ECS_ALTERNATIVE.md` §0) are not
  provisioned by this stack — `MANAGED_INSTANCES` needs its own capacity provider with an
  infrastructure role and EC2 instance profile; `EC2` needs container instances with Mountpoint for
  Amazon S3 already mounted via user-data. Both are real follow-up work, not yet attempted here.
- Spot capacity behavior, real Spot interruptions, and `EcsTaskSupervisor`'s relaunch logic are
  exercised by this environment but not validated by it automatically — that still requires
  actually running `aws-ecs:build` against it and observing the result.
