# scaleout-build-maven-plugin

A Maven plugin that computes a GraalVM/Mandrel native-image build matrix (build kind ×
architecture) and scales the cells that don't match your local host's architecture out to remote
workers — AWS ECS Fargate today — instead of requiring your own machine to cross-compile or run
every matrix cell in parallel locally.

## Why

Producing native binaries for multiple architectures (e.g. `x86_64` and `arm64`) from a single CI
runner or developer machine means either slow QEMU emulation or genuinely owning hardware for
every target architecture. This plugin offloads the cells your host can't build natively to
short-lived remote workers, using S3 as a content-addressed staging layer for classpath jars and
build outputs, and attaches the resulting binaries back to the reactor with per-architecture
classifiers.

## Modules

- **`scaleout-build-shared`** — build matrix types, staging layout, and the native-image
  executor shared by the Maven plugin and the remote worker.
- **`scaleout-build-maven-plugin`** — the plugin itself, bound to the `package` phase's
  `aws-ecs:build` goal.
- **`scaleout-build-agent`** — the container image that runs on the remote worker, executing one
  matrix cell and exiting.
- **`scaleout-build-control-plane-api`** — the control plane's wire contract: JAX-RS interfaces,
  request/response records, and a SigV4 client filter. Shared by the service, the plugin, and any
  other client (a CLI, an MCP server).
- **`scaleout-build-ecs`** — ECS task orchestration, S3 staging, and native-image input planning,
  shared by the plugin and the control-plane service.
- **`scaleout-build-control-plane`** — the Quarkus service that holds the ECS/S3/CloudWatch
  permissions so developers need none. Runs as a Lambda behind a Function URL.
- **`scaleout-build-control-plane-reaper`** — scheduled Lambda that stops the tasks of builds whose
  client died.
- **`scaleout-build-control-plane-infra`** — an AWS CDK (Java) app provisioning the control plane and
  the ECS data plane it drives, as one stack. Not part of the plugin's release artifact.

See [`docs/examples/scaleout-build-example-app`](docs/examples/scaleout-build-example-app) for a
real, runnable example, verified end to end against AWS: native-image compilation for both
`x86_64` and `arm64`, offloaded to ECS Fargate tasks.

## License

[Elastic License 2.0 (ELv2)](LICENSE) — free to use, modify, and redistribute; the only
restriction is offering this software to third parties as a hosted or managed service exposing
its functionality.
