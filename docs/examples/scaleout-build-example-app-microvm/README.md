# Example: scaleout-build example app (Lambda MicroVM backend)

The same minimal, reflection-free application as
[`scaleout-build-example-app`](../scaleout-build-example-app), scoped to `arm64` only, to exercise
`scaleout-build:build` against the **Lambda MicroVM backend** rather than ECS Fargate.

Lambda MicroVMs only support `arm64` — there is no `x86_64` MicroVM, so this example has no
multi-architecture matrix the way the Fargate one does. See
[`configuration.md`'s "Choosing a backend for `arm64`"](../../user-guides/configuration.md#choosing-a-backend-for-arm64)
for why MicroVM is opt-in, not the default: it is the more expensive backend for a sustained,
resource-saturating workload like `native-image`.

## What it verifies

Last run against a MicroVM-enabled control plane in `us-east-1`:

| | |
|---|---|
| `arm64` binary | `ELF 64-bit LSB executable, ARM aarch64, dynamically linked ... for GNU/Linux 3.7.0, stripped` — 13,241,272 bytes |
| Attached classifier | `native-linux-arm64` |
| Backend | Lambda MicroVM (`[NATIVE/ARM64] -> RUNNING`, launched via `RunMicrovm`) |
| Wall-clock | 1 minute 5 seconds, end to end |

## Setup

This example needs a control plane deployed with the MicroVM backend actually enabled —
`scaleout.micro-vm.enabled=true` plus a built, active MicroVM image (`imageArn`/`imageVersion`).
It is **not** enabled by default; see
[`microvm-build-backend-phase1.md`](../../design/control-plane/microvm-build-backend-phase1.md)
for how an operator provisions it.

1. **Get the endpoint** from whoever deployed the control plane, or read it back:

   ```bash
   aws ssm get-parameter --name /scaleout-build/control-plane/endpoint \
       --query Parameter.Value --output text
   ```

2. **Copy the template and set it:**

   ```bash
   cp deployment.properties.example deployment.properties
   # edit scaleout-build.endpoint=
   ```

   `deployment.properties` is gitignored, for the same reason as the Fargate example: it holds
   nothing account-specific beyond the endpoint.

3. **Build:**

   ```bash
   mvn package
   ```

   Because `architectures` is `arm64` only, and this deployment has MicroVM enabled, the single
   cell dispatches to MicroVM under `auto` (the default `backendPreference`). To force Fargate
   instead on a MicroVM-default deployment — e.g. because you know your real build saturates CPU
   and memory the whole way through and want the cheaper backend — add
   `-Dscaleout-build.backendPreference=fargate`.

4. **Collect:**

   ```bash
   file target/scaleout-build/remote-artifacts/NATIVE-ARM64/scaleout-build-example-app-microvm
   ```

## Prerequisites

Only what [the quick start](../../user-guides/quick-start.md) lists: Java 17+, Maven, a deployed
control plane with MicroVM enabled, and AWS credentials carrying `lambda:InvokeFunctionUrl` and
`lambda:InvokeFunction`.

No GraalVM or Mandrel locally — the remote worker (now a Lambda MicroVM, not an ECS Fargate task)
carries the toolchain.
