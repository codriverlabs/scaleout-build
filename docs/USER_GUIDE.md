# User guide

Build GraalVM native images for architectures you don't have hardware for, without holding any AWS
permissions beyond invoking one function.

This guide is for people *using* the plugin. For how it works internally see
[`docs/design/control-plane/`](design/control-plane/); for what a build costs see
[`COST_ANALYSIS.md`](COST_ANALYSIS.md).

## What problem this solves

GraalVM cannot cross-compile. A native image is built by, and for, the machine it runs on — so producing an
`arm64` binary from an `x86_64` host means either QEMU emulation (slow enough to be impractical for
`native-image`) or owning hardware for every target.

This plugin computes a build matrix of *build kind × architecture*, runs locally whatever matches your host,
and sends the rest to short-lived remote workers — one per cell, in parallel. Artifacts come back attached to
your Maven build with per-architecture classifiers.

**It is not a general build accelerator.** For a single architecture matching your host, offloading is
strictly slower and costs money: a local build has no upload, no image pull, and no provisioning delay. The
plugin runs host-matching cells locally for exactly that reason.

**Where it is the only option:** a hosted agent sandbox you do not control. See
[Running in an ephemeral agent sandbox](#running-in-an-ephemeral-agent-sandbox).

## Prerequisites

1. **A deployed control plane.** Someone in your organisation runs it; ask them for the endpoint URL. It
   looks like `https://<id>.lambda-url.<region>.on.aws/`.
2. **AWS credentials with two permissions** on that function, and nothing else:

   ```
   lambda:InvokeFunctionUrl
   lambda:InvokeFunction
   ```

   No ECS, S3, CloudWatch, or ECR access. If you were previously given those for native builds, they can be
   revoked.
3. **Java 17+** to run Maven. The remote workers supply their own GraalVM, so you do **not** need GraalVM or
   Mandrel installed — unless you also want host-matching cells built locally.

## Quickstart

Three steps. Pick your framework's row in [Framework support](#framework-support) first, because two of them
need one extra command.

### 0. Authenticate to GitHub Packages

The Maven artifacts are on GitHub Packages, which **requires a token even for public packages** — unlike
ghcr.io, where the agent image is anonymously pullable. This is a GitHub limitation, not a choice here:

> In most registries, to pull a package, you must authenticate with a personal access token or
> `GITHUB_TOKEN`, regardless of whether the package is public or private. However, in the Container
> registry, public packages allow anonymous access and can be pulled without authentication.
>
> — [About permissions for GitHub Packages](https://docs.github.com/en/packages/learn-github-packages/about-permissions-for-github-packages)

Confirmed against this project's own published artifacts: an unauthenticated `GET` of the plugin's `.pom`
returns **401**, and the same URL with a token returns 302. So a `read:packages` token is required even
though the repository and the package are both public. You will find forum posts claiming otherwise; they
are wrong.

In `~/.m2/settings.xml`:

```xml
<servers>
  <server>
    <id>github</id>
    <username>YOUR_GITHUB_USERNAME</username>
    <password>YOUR_TOKEN_WITH_read:packages</password>
  </server>
</servers>
```

And the repository, in your POM or `settings.xml`:

```xml
<pluginRepositories>
  <pluginRepository>
    <id>github</id>
    <url>https://maven.pkg.github.com/codriverlabs/scaleout-build</url>
  </pluginRepository>
</pluginRepositories>
```

### 1. Add the plugin

```xml
<plugin>
    <groupId>ai.codriverlabs</groupId>
    <artifactId>scaleout-build-maven-plugin</artifactId>
    <version><!-- see releases --></version>
    <executions>
        <execution>
            <phase>package</phase>
            <goals><goal>build</goal></goals>
        </execution>
    </executions>
    <configuration>
        <buildKinds>
            <buildKind>native</buildKind>
        </buildKinds>
        <architectures>
            <architecture>x86_64</architecture>
            <architecture>arm64</architecture>
        </architectures>
        <endpoint>https://REPLACE.lambda-url.eu-west-1.on.aws/</endpoint>
    </configuration>
</plugin>
```

### 2. Build

```bash
mvn package
```

### 3. Collect

Artifacts land in `target/scaleout-build/remote-artifacts/NATIVE-<ARCH>/` and are attached to the reactor with
classifiers like `native-linux-arm64-my-app`.

```bash
file target/scaleout-build/remote-artifacts/NATIVE-ARM64/my-app
# ELF 64-bit LSB executable, ARM aarch64, ...
```

**Keep the endpoint out of a shared POM.** Read it from a gitignored properties file instead — see
[the example app](examples/scaleout-build-example-app) for the pattern, which uses `properties-maven-plugin`
bound to `validate`.

## Framework support

The plugin detects how your project builds; you do not choose a mode. Some frameworks need one command first,
to make them emit their `native-image` arguments instead of running the compile themselves.

| Your project | Extra step | Detected by | Verified |
|---|---|---|---|
| **Plain GraalVM** | none | nothing else matched | Example app, both arches |
| **Quarkus** | build with `sources-only` (below) | `target/native-sources/` exists | 3.39.4, 233 deps, both arches |
| **Spring Boot AOT** | `mvn native:write-args-file` (below) | `argsFileDirectory` is set | Spring Boot 4.1.0, both arches |
| **Helidon** | same as Spring Boot | `argsFileDirectory` is set | **Not tested** — same code path |

### Quarkus

Make Quarkus emit sources rather than compile:

```bash
mvn package -Dquarkus.native.enabled=true -Dquarkus.native.sources-only=true
```

The plugin finds `target/native-sources/` on its own; no configuration needed.

### Spring Boot AOT and Helidon

These build through `native-maven-plugin`, whose `write-args-file` goal is **not** bound to Spring Boot's
`native` profile, so run it explicitly:

```bash
mvn -Pnative package          # runs AOT processing
mvn native:write-args-file    # writes target/native-image-<random>.args
mvn scaleout-build:build -Dscaleout-build.argsFileDirectory=target
```

`argsFileDirectory` has **no default** — the plugin refuses to guess, because a wrong guess would silently
fall through to deriving arguments from the classpath and produce a binary that builds and then fails at run
time.

The directory must contain exactly one `*.args` file. `write-args-file` uses a randomized name, so stale files
accumulate across builds; the plugin refuses to choose rather than picking one.

That argfile is written for the machine that produced it — absolute paths throughout — so the plugin
**rewrites** it: classpath jars into `lib/`, classpath *directories* staged as trees so
`META-INF/native-image/` survives, `-H:ConfigurationFileDirectories` staged and rewritten, `-o` redirected
into the staging output directory.

Verified against `spring-petclinic` (Spring Boot 4.1.0, `native-maven-plugin` 1.1.1) on the deployed control
plane: 565 files staged, 0 absolute paths remaining, binaries of 210,767,112 B (`x86_64`) and 204,082,456 B
(`arm64`); the `x86_64` binary starts AOT-processed in 0.293 s and serves its endpoints. Checked against a
control build — the unmodified argfile run through `native-image` in a container on the same Mandrel 25.0.4.1 —
which produced an identical-length binary behaving identically on every endpoint.

> **One known upstream gap in petclinic specifically.** Its `/vets.html` fails with
> `MissingReflectionRegistrationError` on `org.thymeleaf.expression.Numbers.sequence` — **identically in the
> control build**, so it is a gap between Thymeleaf's published reachability metadata and what that
> pagination template exercises, not something this plugin introduces.

## Running in an ephemeral agent sandbox

Hosted agent sandboxes — [Kiro cloud sessions](https://kiro.dev/docs/cloud-sessions/), a CI container, any
per-session MicroVM — are a good fit, and for `arm64` usually the *only* fit.

**If the sandbox is `x86_64` and you cannot choose otherwise, `arm64` is not producible inside it.** GraalVM
does not cross-compile, and QEMU emulation of a compile that saturates 4 vCPU for minutes is not a practical
substitute. Kiro cloud sessions are `x86_64` in `us-east-1` with no architecture selection (observed, not
published), which is the common case rather than an unusual one.

Offloading also means the client needs **no GraalVM or Mandrel toolchain**:

```
-Dscaleout-build.forceRemote=true
```

Every cell then goes to the control plane and the local `native-image` path is never reached. The build
environment is the agent container image in ECR, pinned and multi-arch, so the sandbox does not have to
reproduce a toolchain it was never given.

The sandbox needs:

| | |
|---|---|
| Maven and a JDK 17+ | for the client and, with Quarkus, augmentation |
| `scaleout-build.endpoint` | the control plane Function URL |
| AWS credentials with the two IAM permissions | set as sandbox environment variables |
| Egress to the Function URL | a public HTTPS endpoint with `AWS_IAM` auth |

**Deploy the control plane in the same region as the sandbox.** Otherwise every artifact download crosses a
region boundary and is billed as inter-region transfer — about 256 MB per two-architecture build.

Trade-offs for this topology, including what offloading does *not* save when sandbox compute is bundled into a
subscription, are in [`COST_ANALYSIS.md`](COST_ANALYSIS.md) §8.

## Configuration reference

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

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `scaleout-build.endpoint is required` | Not set, or the properties file was not read | A shared POM needs `properties-maven-plugin` bound to `validate` |
| `403` on the first call | Credentials lack `lambda:InvokeFunctionUrl` **and** `lambda:InvokeFunction` | Both are needed; the second is easy to miss |
| `contains no *.args file` | `write-args-file` was not run, or ran elsewhere | Run it, and point `argsFileDirectory` at its output directory |
| `contains N *.args files` | Stale argfiles from earlier builds | Delete the old ones; the name is randomized so they accumulate |
| `Found native-sources but no native-image.args` | An ordinary Quarkus native build left the directory behind | Re-run with the `sources-only` properties |
| `Classpath entry … does not exist` | The argfile outlived a `mvn clean` | Re-run `native:write-args-file` after a full build |
| `Cannot determine the main class` | Plain GraalVM project with no `Main-Class` manifest entry | Set `mainClass` |
| `Runtime classpath entry … is a directory` | A reactor dependency is not packaged | `mvn package` or `mvn install` it first |
| Binary builds, then fails at run time | Framework step skipped, so arguments were derived from the classpath | Follow the framework step above |
| Build slower than local | A host-matching cell was offloaded | Remove `forceRemote` |
| Cell fails with no obvious reason | The remote log is the record | Read the streamed output; the agent logs the full `native-image` invocation |

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

## Limits worth knowing

- **An architecture matching your host is better built locally.** The plugin does this by default; only
  `forceRemote` overrides it.
- **`jvm` build kind does not need remote workers** — it produces an architecture-neutral jar. It is in the
  matrix for completeness.
- **PGO does not parallelise within an architecture.** Instrument → run workload → optimize is inherently
  ordered, so those cells are sequential per architecture and only concurrent across them.
- **Do not compare binaries by digest.** `native-image` output is not bit-reproducible: two builds of
  identical inputs with the same toolchain produced binaries of *exactly* the same length differing in
  135,362,134 bytes, starting at the GNU build-id. Compare architecture, length, and behaviour instead.
- **8 GiB of worker memory is a floor, not a preference.** Peak RSS is dominated by native memory and the
  image heap rather than the Java heap, so capping the builder's heap does not reduce it; 4 GiB OOMs on a
  233-dependency project.
- **A Spot interruption fails the build.** There is no server-side retry — see
  [`design/control-plane/HANDOVER.md`](design/control-plane/HANDOVER.md). Set
  `-c fargateCapacityStrategy=on-demand-preferred` at deploy time where a lost build is expensive.
- **Helidon is untested.** It shares the Spring Boot code path, which is verified, but no Helidon project has
  been run through it.
