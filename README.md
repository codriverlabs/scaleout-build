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
- **`scaleout-test-infra`** — an AWS CDK (Java) app that provisions a minimal, disposable AWS
  environment for exercising `aws-ecs:build` against real AWS. Not part of the plugin's release
  artifact.

See [`docs/examples/scaleout-build-example-app`](docs/examples/scaleout-build-example-app) for a
real, runnable example, verified end to end against AWS: native-image compilation for both
`x86_64` and `arm64`, offloaded to ECS Fargate tasks.

## License

[Elastic License 2.0 (ELv2)](LICENSE) — free to use, modify, and redistribute; the only
restriction is offering this software to third parties as a hosted or managed service exposing
its functionality.
