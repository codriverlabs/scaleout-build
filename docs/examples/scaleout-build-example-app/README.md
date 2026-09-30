# Example: scaleout-build example app

A minimal, reflection-free application that exercises `scaleout-build:build` end to end against real AWS —
`native-image` compilation for both `x86_64` and `arm64`, offloaded to short-lived ECS Fargate tasks.

Two dependencies, so it finishes in about two minutes and the interesting part is the pipeline rather than the
compile.

## What it verifies

Last run against the deployed control plane in `eu-west-1`:

| | |
|---|---|
| `x86_64` binary | `ELF 64-bit LSB executable, x86-64` — 13,372,680 bytes |
| `arm64` binary | `ELF 64-bit LSB executable, ARM aarch64` — 13,241,624 bytes |
| Attached classifiers | `native-linux-x86_64-…`, `native-linux-arm64-…` |

Both byte-identical to the pre-migration baseline, which is how the control-plane migration was checked: the
pipeline changed completely, the output did not.

## Setup

One value. That is the point of the example.

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

   `deployment.properties` is gitignored. It holds nothing account-specific beyond the endpoint — no bucket,
   cluster, role, subnet, security group, log group or image, because a client able to set those could choose
   what code runs with the task role's permissions. See
   [`storage-layout-and-isolation.md`](../../design/control-plane/storage-layout-and-isolation.md).

3. **Build:**

   ```bash
   mvn package
   ```

4. **Collect:**

   ```bash
   file target/scaleout-build/remote-artifacts/NATIVE-ARM64/scaleout-build-example-app
   ```

## How the endpoint reaches the plugin

The POM binds `properties-maven-plugin` to the `validate` phase so `${scaleout-build.endpoint}` resolves
before `package` runs the build goal. That is the pattern to copy for a shared POM — it keeps a
deployment-specific URL out of version control without every developer passing `-D` on the command line.

Worth knowing if you adapt it: an earlier version of this POM was broken by an index-based edit that clobbered
the `properties-maven-plugin` configuration, and the failure looked like an unresolved property rather than a
bad edit.

## Prerequisites

Only what [the quick start](../../user-guides/quick-start.md) lists: Java 17+, Maven, a deployed control
plane, and AWS credentials carrying `lambda:InvokeFunctionUrl` and `lambda:InvokeFunction`.

No GraalVM or Mandrel locally — the remote workers carry the toolchain. The `x86_64` cell will build locally
if that matches your host; add `-Dscaleout-build.forceRemote=true` to send every cell remotely.
