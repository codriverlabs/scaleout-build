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

### The case where the chip is not the problem

The two cases above are about architecture. This one is about **memory**, and it needs no cross-compiling at
all — it applies to the chip you already have.

A developer hosting several agent sessions locally — `kiro-cli` sessions against different repositories, each
with its own MCP servers — is already using real memory before any build starts. Roughly:

```
5 sessions x ~5 GB                = 25 GB
OS, MCP servers, headroom         =  4 GB
                                    29 GB   -> a 32 GB machine
+ two concurrent native compiles  = 40 GB   -> a 64 GB machine
```

A `native-image` compile needs about 5.5 GB and saturates 4 vCPU. One is survivable; two at once forces the
next machine size up, permanently, for a few minutes of work a day.

| | eu-west-1 on-demand |
|---|---|
| `r6a.2xlarge` — 8 vCPU / 64 GiB, sized so sessions *and* compiles fit | $370.55/month |
| `r6a.xlarge` — 4 vCPU / 32 GiB, enough when compiles go elsewhere | **$185.27/month** |

**A 50% cut, $185/month, fixed.** At 1.3 cents a build you would have to run about **486 builds a day** — 97
each across five developers — before the offload cost ate the machine saving.

One caveat, since it is easy to over-claim: the saving needs the vCPU count to fall too, not just the memory.
An 8 vCPU / 32 GiB machine (`m7a.2xlarge`, $377/month) costs *more* than the 64 GiB one. Dropping to 4 vCPU is
only reasonable **because** the compiles left — sessions spend most of their time waiting on a model, not
computing.

## When this is the wrong tool

Four cases. The first is the common one.

**You only need the chip you already have, and memory is not tight.** Then don't use this. Building locally is
faster and free — no upload, no image pull, no waiting for a machine to start. The plugin builds host-matching
work locally by default for exactly that reason; you have to set `forceRemote` to override it.

The memory qualifier matters: if concurrent agent sessions are competing for RAM, a local build is **not**
free — it sets your machine size permanently. See
[above](#the-case-where-the-chip-is-not-the-problem).

**You already own the hardware.** If the machines exist and are paid for, the marginal cost of a local build
is electricity, and nothing here competes with that.

**You can keep machines genuinely busy, and you will commit for three years.** This is the only volume
argument that survives, and it is narrower than it looks.

An earlier version of this section claimed that past roughly 800 builds a day you should just rent machines,
7× cheaper at 6,000 a day. **That was wrong**, because it treated an always-on machine as having unlimited
capacity. It does not: a `native-image` compile saturates 4 vCPU for about 4.3 minutes, so one 4-vCPU machine
finishes **about 335 builds a day** at a theoretical 100% utilisation. Always-on capacity has to scale with
volume just as offloading does — 1,000 builds a day needs three machine pairs, 6,000 needs eighteen. Machine
size does not help: a 4× larger instance costs 4× and completes 4× as many, so the cost per build is flat.

Compared per build, both architectures, at the same capacity type:

| Utilisation of your own machines | Always-on, on-demand | Always-on, 3-yr commitment |
|---|---|---|
| 100% (unreachable in practice) | $0.0247 | **$0.0131** |
| 70% | $0.0354 | $0.0187 |
| 50% | $0.0495 | $0.0262 |

Against **$0.0127** offloaded on interruptible capacity, or **$0.0302** offloaded on guaranteed capacity.

So the honest reading:

- **Interruptible is fine for you:** offloading wins everywhere except a three-year commitment run at
  essentially 100% utilisation, where it is a tie ($0.0131 against $0.0127).
- **You need guaranteed capacity:** own machines win *if* you keep them ~100% busy ($0.0247 against $0.0302).
  At 70% utilisation they cost $0.0354 and offloading wins again.

Bursty traffic — which is what agent and CI workloads are — is what makes high utilisation hard. A queue of
builds waiting for a busy machine is the cost of that utilisation, paid in latency rather than dollars.

**You cannot tolerate an occasional lost build.** The default is interruptible capacity, which is about 70%
cheaper and occasionally reclaimed mid-build. There is no automatic retry today, so a reclaimed build fails
and you run it again. Deployments where that is expensive should set
`-c fargateCapacityStrategy=on-demand-preferred` when deploying the control plane. Details in
[`docs/COST_ANALYSIS.md`](docs/COST_ANALYSIS.md).

## Using it

**To deploy the control plane** (once per team, by whoever owns the AWS account) download the installer
from a [release](../../releases) — it carries a pre-synthesized CDK app, so it needs only the AWS CLI,
Node 20+ and Docker, with no Maven or JDK:

```bash
tar xzf scaleout-build-installer-<version>.tar.gz
cd scaleout-build-installer-<version>
./install.sh --region eu-west-1
```

**To use it as a developer**, see the [**user guide**](docs/USER_GUIDE.md) for setup, framework-specific steps (Quarkus, Spring Boot AOT,
Helidon, plain GraalVM), the full configuration reference, and troubleshooting.

A developer needs one configuration value — the control plane endpoint — and two IAM permissions
(`lambda:InvokeFunctionUrl`, `lambda:InvokeFunction`). No ECS, S3, CloudWatch or ECR access.

## What a release publishes

Three destinations, because the components have different delivery constraints.

| Artifact | Published to | How it reaches your account |
|---|---|---|
| **Build agent** — Mandrel toolchain, multi-arch | `ghcr.io/codriverlabs/scaleout-build/scaleout-build-agent:<version>` | `install.sh` copies it **ghcr.io → your private ECR**, registry to registry, no local pull |
| **Control-plane Lambda** — native `arm64`, 256 MB | in the installer tarball, and standalone on the release | `cdk deploy` uploads it to the CDK bootstrap S3 bucket |
| **Reaper Lambda** — JVM jar | same | same |
| **Installer** — pre-synthesized CDK app + `install.sh` | GitHub Release asset | `curl`, `tar`, `./install.sh` |
| **Maven artifacts** — plugin, API, shared, ECS | GitHub Packages | `mvn` from a developer machine or CI |

**An image for the agent and zips for the Lambdas** is forced rather than stylistic. Lambda
[cannot pull container images from anywhere but ECR](https://docs.aws.amazon.com/AmazonECR/latest/userguide/migrate-from-third-party.html),
and a Lambda image must exist at *function-creation* time — whereas the agent image is read at ECS *task
launch*, so copying it into ECR fits naturally. Going the image route for the Lambdas too would force the
install into repos → copy images → deploy stack instead of a single `cdk deploy`, and trade a measured cold
start (480–524 ms) for an unmeasured one.

Nothing in your account talks to ghcr.io after installation: the agent image lives in your ECR, and the
Lambda code in your CDK bootstrap bucket.

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
