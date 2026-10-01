# scaleout-build

Maven plugin and AWS control plane for **cross-architecture GraalVM native builds** — compile for a CPU
architecture your machine does not have, from your normal `mvn package`.

Built with **Quarkus 3**, **GraalVM native image** (Java 25), **AWS CDK**, and **Mandrel**.

[![Release](https://img.shields.io/github/v/release/codriverlabs/scaleout-build?include_prereleases)](https://github.com/codriverlabs/scaleout-build/releases)
[![Tests](https://img.shields.io/badge/tests-175%20passing-brightgreen)](.github/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-ELv2-blue)](LICENSE)

---

## What is scaleout-build?

GraalVM cannot cross-compile: a native image is built by, and for, the machine it runs on. Producing an
`arm64` binary from an `x86_64` host therefore means QEMU emulation, which is impractical for a compile that
saturates 4 vCPU for minutes, or owning hardware for every target.

scaleout-build rents the missing architecture for the few minutes the compile takes:

- Computes a build matrix of **build kind × architecture**
- Runs whatever matches your host **locally**, and sends the rest to short-lived **AWS ECS Fargate** workers,
  one per cell, in parallel
- Streams every cell's logs back into your Maven output
- Attaches the binaries to your reactor with per-architecture classifiers

A developer needs **one configuration value** and **two IAM permissions**. No ECS, S3, CloudWatch or ECR
access, and **no local GraalVM toolchain** — the workers bring their own compiler.

On a real 233-dependency Quarkus application, both binaries cost about **1.3 cents** of cloud time and
complete in five to nine minutes each, running concurrently.

> **Read this before adopting**: [Why offload a native build?](docs/WHY_OFFLOAD.md) covers the three
> situations where this pays — including one that needs no cross-compiling at all — and, just as importantly,
> [when it is the wrong tool](docs/WHY_OFFLOAD.md#when-this-is-the-wrong-tool).

---

## Components

| Component | Role |
|---|---|
| `scaleout-build-maven-plugin` | The plugin. Runs on a developer machine or CI, bound to the `package` phase |
| `scaleout-build-control-plane` | Quarkus service on Lambda behind a Function URL. Holds the ECS/S3/CloudWatch permissions so developers need none |
| `scaleout-build-agent` | Container that runs one matrix cell and exits. Carries the Mandrel toolchain |
| `scaleout-build-control-plane-api` | Wire contract: JAX-RS interfaces, DTOs, SigV4 client filter |
| `scaleout-build-ecs` | ECS orchestration, S3 staging, native-image input planning |
| `scaleout-build-control-plane-reaper` | Scheduled Lambda that stops the tasks of builds whose client died |
| `scaleout-build-control-plane-infra` | AWS CDK app provisioning the control plane and its ECS data plane as one stack |
| `scaleout-build-shared` | Build matrix types, staging layout, native-image executor |

---

## Installation

### Control plane (once per team)

Whoever owns the AWS account installs it. Two options:

**Option A — Docker (no dependencies):**

```bash
docker run --rm -it \
  -v ~/.aws:/root/.aws:ro \
  -e AWS_PROFILE=${AWS_PROFILE:-default} \
  -e AWS_REGION=eu-west-1 \
  ghcr.io/codriverlabs/scaleout-build/installer:latest \
  --region eu-west-1 --yes
```

No Node, no CDK, no AWS CLI on your machine — everything is inside the image.

**Option B — tarball (lighter download, ~40 MB):**

```bash
curl -fsSL -O https://github.com/codriverlabs/scaleout-build/releases/latest/download/scaleout-build-installer-<version>.tar.gz
tar xzf scaleout-build-installer-<version>.tar.gz
cd scaleout-build-installer-<version>
./install.sh --region eu-west-1
```

Needs the AWS CLI, Node 20+ and Docker locally.

It deploys the stack, copies the agent image from ghcr.io into your private ECR, and prints the endpoint plus
the two IAM permissions your developers need. See `./install.sh --help` for capacity strategy and other
options.

### Developer (per project)

See the [**Quick Start**](docs/user-guides/quick-start.md) — five steps, about five minutes.

### Uninstalling

```bash
npx aws-cdk@2 destroy ScaleoutBuildControlPlane --app cdk.out
```

The S3 staging bucket and DynamoDB build table are created with `RemovalPolicy.RETAIN` and survive
deliberately — delete them by hand once you are sure.

---

## Quick Example

```bash
# Quarkus: emit sources rather than compiling locally
mvn package -Dquarkus.native.enabled=true -Dquarkus.native.sources-only=true

# Build both architectures; the host-matching one runs locally
mvn package -Dscaleout-build.endpoint=https://REPLACE.lambda-url.eu-west-1.on.aws/

# Collect
file target/scaleout-build/remote-artifacts/NATIVE-ARM64/my-app
# ELF 64-bit LSB executable, ARM aarch64, version 1 (SYSV), dynamically linked
```

Inside a hosted agent sandbox, where you cannot choose the CPU architecture, add
`-Dscaleout-build.forceRemote=true` and every cell goes remote — see
[Agent sandboxes](docs/user-guides/agent-sandboxes.md).

---

## IAM Requirements

A developer needs exactly two permissions on the control plane function:

```bash
# Invoke the control plane, and nothing else
lambda:InvokeFunctionUrl
lambda:InvokeFunction
```

The control plane itself holds the ECS, S3, CloudWatch and ECR permissions, with `iam:PassRole` scoped to
exactly two role ARNs conditioned on `iam:PassedToService=ecs-tasks.amazonaws.com`. If your developers
currently hold broader permissions for native builds, those can be revoked — see
[`storage-layout-and-isolation.md`](docs/design/control-plane/storage-layout-and-isolation.md).

---

## What a release publishes

Each component ships in **exactly one form** — an image *or* a zip, never both. Three destinations, because
the components have different delivery constraints.

| Artifact | Published to | How it reaches your account |
|---|---|---|
| **Build agent** — Mandrel toolchain, multi-arch | `ghcr.io/codriverlabs/scaleout-build/scaleout-build-agent:<version>` | `install.sh` copies it **ghcr.io → your private ECR**, registry to registry, no local pull |
| **Control-plane Lambda** — native `arm64` **zip**, 256 MB | in the installer tarball, and standalone on the release | `cdk deploy` uploads it to the CDK bootstrap S3 bucket |
| **Reaper Lambda** — JVM **jar** | same | same |
| **Installer** — pre-synthesized CDK app + `install.sh` | GitHub Release asset | `curl`, `tar`, `./install.sh` |
| **Maven artifacts** — plugin, API, shared, ECS | **Maven Central** (primary) and GitHub Packages | `mvn` from a developer machine or CI — no token required for Central |

**An image for the agent and zips for the Lambdas** is forced rather than stylistic. Lambda
[cannot pull container images from anywhere but ECR](https://docs.aws.amazon.com/AmazonECR/latest/userguide/migrate-from-third-party.html),
and a Lambda image must exist at *function-creation* time — whereas the agent image is read at ECS *task
launch*, so copying it into ECR fits naturally. Going the image route for the Lambdas too would force the
install into repos → copy images → deploy stack instead of a single `cdk deploy`, and trade a measured cold
start (480–524 ms) for an unmeasured one.

**There is no Lambda container image, in any release.** The zips appearing in two places — inside the
tarball and standalone on the release — are the same bytes reachable two ways: the tarball is what
`install.sh` uses, and the standalone assets are for anyone deploying through their own CDK, Terraform, or
the console.

Nothing in your account talks to ghcr.io after installation: the agent image lives in your ECR, and the
Lambda code in your CDK bootstrap bucket.

---

## Security

- Per-developer isolation: `ownerHash` is derived server-side from the caller's identity and never taken from
  client input
- Function URL is `AuthType: AWS_IAM`; every request carries a SigV4 signature
- The client holds no AWS credentials beyond its own — staging uses presigned URLs
- Content-addressed staging is keyed by **blob** digest per owner, never by project digest

Report a vulnerability privately: see [`SECURITY.md`](SECURITY.md).

---

## Development

```bash
mvn -B clean verify              # 175 tests
./scripts/verify-synth.sh        # CDK synthesises in both deployment modes
./scripts/verify-agent-image.sh <image-uri>
```

See [`CONTRIBUTING.md`](CONTRIBUTING.md) for the conventions that matter here — each one exists because
breaking it caused a real bug.

---

## Documentation

| Guide | Description |
|---|---|
| [Why offload a native build?](docs/WHY_OFFLOAD.md) | The three motivations, and when this is the wrong tool |
| [Quick Start](docs/user-guides/quick-start.md) | Two-architecture native build in 5 minutes |
| [Framework Support](docs/user-guides/frameworks.md) | Quarkus, Spring Boot AOT, Helidon, plain GraalVM |
| [Agent Sandboxes](docs/user-guides/agent-sandboxes.md) | Running where you cannot choose the CPU architecture |
| [Configuration Reference](docs/user-guides/configuration.md) | All 16 properties, and how a build is bounded |
| [Troubleshooting](docs/user-guides/troubleshooting.md) | Symptom, cause, fix |
| [Cost Analysis](docs/COST_ANALYSIS.md) | Measured per-build cost, and comparison against always-on machines |
| [Control Plane Design](docs/design/control-plane/scaleout-builder-control-plane.md) | Architecture, wire contract, endpoints |
| [Storage Layout & Isolation](docs/design/control-plane/storage-layout-and-isolation.md) | Per-owner CAS, ownerHash derivation |
| [SigV4 Client Signing](docs/design/control-plane/sigv4-client-signing.md) | How the client authenticates |
| [Lambda Resource Usage](docs/design/control-plane/lambda-resource-usage.md) | Measured memory, duration, cold start |
| [Fargate Task Resource Usage](docs/design/control-plane/fargate-task-resource-usage.md) | Measured peak RSS, CPU, compile times |
| [GraalVM Version Matrix Axis](docs/design/control-plane/graalvm-version-matrix-axis.md) | Design for a version axis |
| [MicroVM Build Backend](docs/design/control-plane/microvm-build-backend.md) | Design for a Lambda MicroVM backend |
| [Publishing to Maven Central](docs/design/ci-cd/publishing-to-maven-central.md) | Readiness state and the publisher-tier question |
| [Handover](docs/design/control-plane/HANDOVER.md) | Current deployed state and outstanding work |

---

## Example

See [`docs/examples/scaleout-build-example-app`](docs/examples/scaleout-build-example-app) for a
real, runnable example, verified end to end against AWS: native-image compilation for both
`x86_64` and `arm64`, offloaded to ECS Fargate tasks.

---

---

## License

[Elastic License 2.0 (ELv2)](LICENSE) — free to use, modify, and redistribute; the only
restriction is offering this software to third parties as a hosted or managed service exposing
its functionality.
