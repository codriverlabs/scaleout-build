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

### In plain terms

Java normally starts slowly. You can compile it down to a native binary that starts in milliseconds, which
is what you want for a cloud service or a command-line tool.

The catch is that **you have to compile on the same kind of chip you are shipping to.** There is no
cross-compiling. Cloud now runs on two chip families — the older Intel/AMD kind (`x86_64`) and the newer ARM
kind (AWS Graviton, same family as Apple Silicon). ARM costs roughly 25% less to run, so plenty of teams want
to ship both.

That leaves two usual options: own and maintain build machines of both kinds, or quietly ship for only one.

This plugin is a third option. When your build needs a chip your machine does not have, it rents that chip in
the cloud for the few minutes the compile takes, builds there, and hands the finished binary back. You run
your normal `mvn package`. The remote workers bring their own compiler, so there is nothing extra to install
locally.

On a real 233-dependency application, both binaries cost **about 1.3 cents** of cloud time — five to nine
minutes each, running in parallel. Add about **0.7 cents** if you are also paying for the machine that runs
Maven, as in CI or an agent sandbox; on a laptop you already own, that part is free. So **1.3 to 2 cents a
build**, and the table [below](#when-this-is-the-wrong-tool) uses the 2-cent figure because it is comparing
against machines you would otherwise rent.

Two honest caveats on that number. Roughly a quarter of the 1.3 cents — log storage, the build-state table,
and staged uploads — is **costed rather than metered**; only the compute and network lines were measured
directly. And it assumes interruptible capacity, which is the default; guaranteed capacity roughly doubles the
compute portion. Full breakdown in [`docs/COST_ANALYSIS.md`](docs/COST_ANALYSIS.md).

### The case where there is no alternative

"Own hardware for every target" assumes you control the machine. In a **hosted agent sandbox you do
not** — and increasingly that is where builds run.

Kiro cloud sessions, for example, provision an `x86_64` sandbox in `us-east-1` with **no way to select a
different architecture** (observed, not published — Kiro documents the sandbox lifecycle but not its
specs). So an `arm64` native binary is not slow to produce there, or expensive. It is **not producible at
all**. Root access and Podman inside the sandbox do not change that: the only local route to a foreign
architecture is QEMU, and emulating a compile that saturates 4 vCPU for 3–5 minutes is not a workaround.

This plugin turns that from blocked into a configuration value:

```
-Dscaleout-build.forceRemote=true
```

The client then needs Maven, a JDK, this plugin, and two IAM permissions — **no GraalVM or Mandrel
toolchain**, because the local `native-image` path is never reached. The build environment is the agent
container image in ECR, pinned and multi-arch, so an ephemeral sandbox does not have to reproduce a
toolchain it was never given.

See [`docs/USER_GUIDE.md`](docs/USER_GUIDE.md#running-in-an-ephemeral-agent-sandbox) for the setup, and
[`docs/COST_ANALYSIS.md`](docs/COST_ANALYSIS.md) §8 for what this does and does not save — hosted sandbox
compute is often bundled into a subscription, so the honest claim is capability, not cost.

## When this is the wrong tool

Three cases, and the first two are the common ones.

**You only need the chip you already have.** Then don't use this. Building locally is faster and free — no
upload, no image pull, no waiting for a machine to start. The plugin builds host-matching work locally by
default for exactly that reason; you have to set `forceRemote` to override it.

**You build a lot.** Renting per build stops making sense at volume, because a machine you keep running is
cheap per build once it is busy. Plain pay-as-you-go rates, no commitments either side, at **2 cents a
build** — the figure that includes renting a machine to run Maven, since that is what the right-hand column
gives you:

| Your volume | This plugin | Two always-on machines, one per chip |
|---|---|---|
| 100 builds/day | **$60/month** | $503/month |
| 600 builds/day | **$361/month** | $503/month |
| 1,000 builds/day | $602/month | **$503/month** |
| 6,000 builds/day | $3,611/month | **$503/month** — 7× cheaper |

The crossover is around **800 builds a day, sustained**. Past that, rent machines. At a few thousand builds a
day it is not a close call, and a Savings Plan on those machines widens the gap further.

This is not a discount to negotiate — it is the shape of the cost. Per-build rental wins when machines would
sit idle, and loses when they would not.

**You cannot tolerate an occasional lost build.** The default is interruptible capacity, which is about 70%
cheaper and occasionally reclaimed mid-build. There is no automatic retry today, so a reclaimed build fails
and you run it again. Deployments where that is expensive should set
`-c fargateCapacityStrategy=on-demand-preferred` when deploying the control plane. Details in
[`docs/COST_ANALYSIS.md`](docs/COST_ANALYSIS.md).

## Using it

See the [**user guide**](docs/USER_GUIDE.md) for setup, framework-specific steps (Quarkus, Spring Boot AOT,
Helidon, plain GraalVM), the full configuration reference, and troubleshooting.

A developer needs one configuration value — the control plane endpoint — and two IAM permissions
(`lambda:InvokeFunctionUrl`, `lambda:InvokeFunction`). No ECS, S3, CloudWatch or ECR access.

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
