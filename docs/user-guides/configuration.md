# User Guide: Configuration Reference

Every parameter is settable as `-Dscaleout-build.<name>` or as a `<configuration>` element.

| Property | Default | Purpose |
|---|---|---|
| `endpoint` | *required* | Control plane Function URL |
| `buildKinds` | all | `jvm`, `native`, `native-pgo-instrument`, `native-pgo-optimize` |
| `architectures` | host + others | `x86_64`, `arm64` |
| `imageName` | `finalName` | Output binary name |
| `mainClass` | from manifest | Required for plain GraalVM with no `Main-Class` |
| `argsFileDirectory` | *none* | Required for Spring Boot AOT and Helidon |
| `forceRemote` | `false` | Send every cell remotely, including host-matching ones |
| `skip` | `false` | Skip the goal entirely |
| `nativeImageCommand` | `native-image` | Local binary, for host-matching cells only |
| `profilePath` | — | PGO profile, for `native-pgo-optimize` |
| `workDirectory` | — | Local staging directory |
| `timeoutMinutes` | `0` → server default (30) | Per-compile timeout, in minutes. `0` asks the control plane for `scaleout.ecs.default-cell-timeout-minutes`; whatever you ask for is clamped to `scaleout.limits.max-cell-timeout-minutes` (120) |
| `overallTimeoutMinutes` | `120` | Whole-build timeout |
| `requestedCpu` | `4096` | Worker vCPU units; 4096 = 4 vCPU |
| `requestedMemory` | `8192` | Worker MiB. 8 GiB is a floor — 4 GiB OOMs on real projects |
| `requestedEphemeralStorageGiB` | `0` | `0` means omit, which yields the free 20 GiB |
| `backendPreference` | `auto` | Which compute backend `arm64` cells should prefer: `auto` (deployment's own default), `fargate` (force Fargate), or `microvm` (force Lambda MicroVM — rejected, not silently substituted, if the deployment does not have it enabled, or if `x86_64` is also requested). See "Choosing a backend for `arm64`" below before setting this to `microvm` |

## Choosing a backend for `arm64`

A deployment operator decides whether `arm64` cells use Fargate or Lambda MicroVM by default
(`scaleout.micro-vm.enabled` on the control plane) — that default is not yours to see or change
from a build. `backendPreference` lets you override it for your own build, in either direction.

**MicroVM is not simply "the newer, better option."** Its billing has a baseline-plus-burst model:
you pay a lower baseline rate and only pay more when your workload actually bursts above it. That
is a real win for bursty, idle-heavy workloads. A `native-image` compile is close to the opposite
shape — it saturates CPU and memory for essentially its entire duration, with no idle period for
the baseline rate to apply to. Measured against AWS's own published per-unit rates, MicroVM's
vCPU-second and memory-GB-second prices run roughly 3–3.7x Fargate's for the same architecture, so
a compile that costs about 1.8¢ on Fargate can cost closer to 5.9¢ on MicroVM for the identical
work. If your deployment defaults `arm64` to MicroVM and your builds are long, CPU/memory-
saturating compiles, set `backendPreference=fargate` to opt back out on a per-build basis.

Set `backendPreference=microvm` instead if your deployment defaults to Fargate but you specifically
want MicroVM's isolation, snapshot-based cold-start, or 8-hour lifetime — or if your build's actual
resource profile has real idle periods between phases, where MicroVM's burst billing could plausibly
beat Fargate's flat rate (not yet validated with real phase-by-phase measurements; try it and
measure your own build before assuming it will).

## How a build is bounded

Three layers, because they fail differently and the outer ones must survive the inner ones being useless.

| Layer | Bound | Fires when |
|---|---|---|
| `native-image` subprocess | `timeoutMinutes` (default 30) | The compile hangs. Reports which command hung |
| The whole container | that budget **+ 10 min**, via coreutils `timeout` in the agent image | Anything else stalls — staging download, artifact upload — or the JVM is too wedged to time itself out |
| The control plane | `overallTimeoutMinutes` (default 120, clamped) plus a reaper that stops tasks whose client stopped heartbeating | The client dies in a way that skips its shutdown hook — `SIGKILL`, OOM, power loss |

You set the first from Maven. The second is derived from it, so raising `timeoutMinutes` raises both. The
third is deployment policy.

A `Ctrl-C` does not rely on any of them: the plugin installs a shutdown hook that cancels the build
synchronously before exiting.

If a cell fails with **exit 124**, the container backstop fired. Since the compile has its own shorter budget,
that usually means a stall in staging or upload rather than a slow compile.
