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
