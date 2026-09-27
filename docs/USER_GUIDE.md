# User guide

Build GraalVM native images for architectures you don't have hardware for, without holding any AWS
permissions beyond invoking one function.

This guide is for people *using* the plugin. For how it works internally see
[`docs/design/control-plane/`](design/control-plane/); for what a build costs see
[`COST_ANALYSIS.md`](COST_ANALYSIS.md).

## What problem this solves

GraalVM cannot cross-compile. A native image is built by, and for, the machine it runs on — so producing
an `arm64` binary from an `x86_64` laptop means either QEMU emulation (slow enough to be impractical for
`native-image`) or owning hardware for every target.

This plugin computes a build matrix of *build kind × architecture*, runs locally whatever matches your
host, and sends the rest to short-lived remote workers — one per cell, in parallel. Artifacts come back
attached to your Maven build with per-architecture classifiers.

**It is not a general build accelerator.** For a single architecture that matches your host, offloading is
strictly slower and costs money: a local build has no upload, no image pull, and no provisioning delay.
The plugin runs host-matching cells locally for exactly that reason.

## Prerequisites

1. **A deployed control plane.** Someone in your organisation runs it; ask them for the endpoint URL. It
   looks like `https://<id>.lambda-url.<region>.on.aws/`.
2. **AWS credentials with two permissions** on that function, and nothing else:

   ```
   lambda:InvokeFunctionUrl
   lambda:InvokeFunction
   ```

   No ECS, S3, CloudWatch, or ECR access. If you were previously given those for native builds, they can
   be revoked.
3. **Java 17+** to run Maven. The remote workers supply their own GraalVM, so you do **not** need
   GraalVM or Mandrel installed unless you also want host-matching cells built locally.

## Quickstart

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
        <mainClass>com.example.Main</mainClass>
        <imageName>my-app</imageName>
        <endpoint>https://REPLACE.lambda-url.eu-west-1.on.aws/</endpoint>
    </configuration>
</plugin>
```

```bash
mvn package
```

Artifacts land in `target/scaleout-build/remote-artifacts/NATIVE-<ARCH>/`.

**Keep the endpoint out of your POM if the POM is shared.** Read it from a gitignored properties file
instead — see [the example app](examples/scaleout-build-example-app) for the pattern, which uses
`properties-maven-plugin` at the `validate` phase.

## Framework support

The plugin detects how your project builds and adapts. You do not choose a mode; you may need to make your
framework emit its arguments first.

| Your project | What you do | Detected by |
|---|---|---|
| **Plain GraalVM** | nothing | no framework output found |
| **Quarkus** | add two properties to the build (below) | `target/native-sources/` exists |
| **Spring Boot AOT** | run `mvn native:write-args-file`, set `argsFileDirectory` | `argsFileDirectory` is set and exists |
| **Helidon** | same as Spring Boot | same |

### Why frameworks need an extra step

A framework native build is not a plain `native-image` invocation. Quarkus augmentation, and Spring Boot's
AOT processing, generate a *complete* command line — feature registrations, reflection and resource
configuration, `--exclude-config` regexes, dozens of `-J-D` properties. None of that can be reconstructed
from a classpath.

So the plugin reuses the argument file your framework generated rather than writing its own. The extra step
is what makes the framework emit that file instead of going straight to a binary.

**If you skip it**, the plugin falls back to deriving arguments from your classpath. That does not error —
it produces a binary that builds successfully and then misbehaves at runtime, because the generated
configuration is missing. Follow the step for your framework.

### Quarkus

```bash
mvn package -Dquarkus.native.enabled=true -Dquarkus.native.sources-only=true
```

Verified against Quarkus 3.39.4. On older Quarkus (before roughly 3.9) the equivalent was
`-Dquarkus.package.jar.type=native-sources`; current versions reject that outright.

Leave `mainClass` and `imageName` unset — the generated argument file already names its own output.

### Spring Boot AOT and Helidon

Both build through GraalVM's `native-maven-plugin`, which can write its arguments to a file:

```bash
mvn native:write-args-file
mvn package -Dscaleout-build.argsFileDirectory=target/<where it wrote>
```

**There is no default path, deliberately.** `write-args-file` takes its location from the
`graalvm.native-image.args-file` property, and this path has not been verified end to end against a real
Spring Boot or Helidon project. Guessing a default would fail silently by falling through to the derived
strategy, so the plugin requires you to say where the file is. The directory must contain a file named
`native-image.args`.

If you use this path, please report what worked — it is the one framework route without a real-project
test behind it.

## Configuration reference

### Required

| Parameter | Property | Notes |
|---|---|---|
| `endpoint` | `scaleout-build.endpoint` | Control plane Function URL. The signing region is parsed from the host, so there is no region to set. |

### Build matrix

| Parameter | Property | Default | Notes |
|---|---|---|---|
| `buildKinds` | `scaleout-build.buildKinds` | `native` | `jvm`, `native`, `native-pgo-instrument`, `native-pgo-optimize` |
| `architectures` | `scaleout-build.architectures` | host | `x86_64` (or `amd64`, `x64`), `arm64` (or `aarch64`) |

The matrix is the cross-product. Two kinds × two architectures is four cells, four remote workers, running
concurrently.

### Build intent

| Parameter | Property | Default | Notes |
|---|---|---|---|
| `mainClass` | `scaleout-build.mainClass` | `Main-Class` manifest entry | Not needed for framework builds |
| `imageName` | `scaleout-build.imageName` | — | Not needed for framework builds |
| `nativeImageCommand` | `scaleout-build.nativeImageCommand` | `native-image` | |
| `extraNativeImageArgs` | — | — | Appended to the `native-image` invocation |
| `extraBuildArgs` | — | — | Appended to a *derived* argument file only |
| `profilePath` | `scaleout-build.profilePath` | — | Required for `native-pgo-optimize`; uploaded as an input |
| `argsFileDirectory` | `scaleout-build.argsFileDirectory` | — | See Spring Boot / Helidon above |

### Execution

| Parameter | Property | Default | Notes |
|---|---|---|---|
| `skip` | `scaleout-build.skip` | `false` | |
| `forceRemote` | `scaleout-build.forceRemote` | `false` | Build host-matching cells remotely too |
| `workDirectory` | `scaleout-build.workDirectory` | `target/scaleout-build` | Where artifacts are downloaded |
| `timeoutMinutes` | `scaleout-build.timeoutMinutes` | `0` (server policy) | Per cell |
| `overallTimeoutMinutes` | `scaleout-build.overallTimeoutMinutes` | `120` | Whole matrix |

### Resource requests

Requests, not settings: the server clamps them to its policy and logs the applied values, so a clamp is
visible rather than silent.

| Parameter | Property | Default |
|---|---|---|
| `requestedCpu` | `scaleout-build.requestedCpu` | `4096` (4 vCPU) |
| `requestedMemory` | `scaleout-build.requestedMemory` | `8192` (8 GiB) |
| `requestedEphemeralStorageGiB` | `scaleout-build.requestedEphemeralStorageGiB` | `0` (server default, 20 GiB) |

Measured: `native-image` **saturates all 4 vCPU** regardless of project size, so reducing CPU lengthens
builds proportionally — a latency trade, not reclaimed slack.

Peak RSS is 5.3–5.6 GB for a 233-jar Quarkus application and 1.2 GB for a two-dependency one, which is why
the default is 8 GiB (about 68% used, the smallest pairing Fargate allows with 4 vCPU). **4 GiB does not
work** for a project of that size, and capping the builder's heap will not make it fit: peak RSS is
dominated by native memory and the image heap being constructed, not the Java heap.

The builder is given `-J-XX:MaxRAMPercentage=80` by default, which is read from the container's cgroup
limit and so stays correct if you resize the task. Pass your own `-J-Xmx` or `-J-XX:MaxRAMPercentage` in
`extraNativeImageArgs` to override it — later `-J` arguments win. Measured on a 233-jar application, the
percentage form did roughly half the garbage collections and finished faster than an absolute `-J-Xmx6g`.

See [`fargate-task-resource-usage.md`](design/control-plane/fargate-task-resource-usage.md).

## What to expect

From a real 233-jar Quarkus application, both architectures concurrently:

| | |
|---|---|
| First build (uploads whole classpath) | ~54 MB uploaded |
| Later builds | 1 input re-uploaded; the rest deduplicated |
| Provisioning + image pull | ~20 s + ~8 s per worker |
| `native-image` | ~3 min per cell |
| Total wall clock | ~6 min for both |

Dependency jars are content-addressed and stable between builds, so they upload once. Your own jar
re-uploads every time — Maven embeds timestamps, so it is never byte-identical.

Logs stream back live, labelled per cell. Long builds reconnect transparently; you do not need to do
anything.

## Cancelling

`Ctrl+C` cancels the remote build and stops the workers. If your client dies in a way that skips
shutdown — `kill -9`, a closed terminal, a crash — a server-side reaper stops the tasks within the
heartbeat grace period, so you are not billed indefinitely.

## Running in an ephemeral agent sandbox

Hosted agent sandboxes — [Kiro cloud sessions](https://kiro.dev/docs/cloud-sessions/), a CI container, any
per-session MicroVM — are a good fit, and for `arm64` they are usually the *only* fit.

**If the sandbox is `x86_64` and you cannot choose otherwise, `arm64` is not producible inside it.** GraalVM
does not cross-compile, and QEMU emulation of a compile that saturates 4 vCPU for minutes is not a practical
substitute. Kiro cloud sessions are `x86_64` in `us-east-1` with no architecture selection (observed, not
published), which is the common case rather than an unusual one.

Offloading also means **the client needs no GraalVM or Mandrel toolchain**. Set:

```
-Dscaleout-build.forceRemote=true
```

Every cell then goes to the control plane and the local `native-image` path is never reached. The build
environment lives in the agent container image in ECR, pinned and multi-arch, so the sandbox does not have
to reproduce a toolchain it was never given.

The sandbox needs:

| | |
|---|---|
| Maven and a JDK | for the client and, with Quarkus, augmentation |
| `scaleout-build.endpoint` | the control plane Function URL |
| AWS credentials with `lambda:InvokeFunctionUrl` and `lambda:InvokeFunction` | set as sandbox environment variables |
| Egress to the Function URL | it is a public HTTPS endpoint with `AWS_IAM` auth |

**Deploy the control plane in the same region as the sandbox.** Otherwise every artifact download crosses a
region boundary and is billed as inter-region transfer — about 256 MB per two-architecture build. Kiro cloud
sessions run in `us-east-1` only.

Cost and capability trade-offs for this topology are in
[`COST_ANALYSIS.md`](COST_ANALYSIS.md) §8 — including what offloading does *not* buy, since hosted sandbox
compute is often bundled into a subscription rather than billed separately.

## Framework support status

| Framework | Status |
|---|---|
| **Quarkus** | Verified end to end — 3.39.4, 233 dependencies, both architectures |
| **Plain GraalVM** via derived classpath | Works; no AOT-generated configuration involved |
| **Spring Boot AOT** | **Not yet wired up.** See below — the remaining work is path relocation |
| **Helidon** | Same, same cause |

Spring Boot native builds are perfectly ordinary GraalVM builds; nothing in their output prevents building
remotely. The gap is on our side: `native-maven-plugin`'s `write-args-file` is designed for local invocation,
so it emits **absolute host paths** (measured against Spring Boot 4.1.0: 107 references, a classpath pointing
into `~/.m2/repository`) and a randomized filename, `native-image-<random>.args`. Those need relocating to
container paths before the argfile can be replayed remotely.

Encouragingly, most of it already works by accident of good design on GraalVM's part: the builder
auto-discovers configuration from `META-INF/native-image/` anywhere on the classpath, and Spring's AOT step
populates `target/classes/META-INF/native-image/` — 58 metadata files covering the same libraries the
argfile's `-H:ConfigurationFileDirectories` entries name. So the AOT configuration travels with the classpath
we already stage.

Until the relocation lands, don't work around it by renaming the argfile — the paths inside still point at
your workstation. Scoped in
[`design/control-plane/matrix-axes-and-image-strategy.md`](design/control-plane/matrix-axes-and-image-strategy.md).

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `scaleout-build.endpoint is required` | Not set, or the properties file was not read | Check the value resolves; a shared POM needs `properties-maven-plugin` bound to `validate` |
| `403` on the first call | Credentials lack `lambda:InvokeFunctionUrl` **and** `lambda:InvokeFunction` | Both are needed; the second is easy to miss |
| Binary builds but fails at runtime, framework project | The extra step was skipped, so arguments were derived | Follow the framework step above |
| `Found native-sources but no native-image.args` | An ordinary native build left the directory behind | Re-run with the sources-only properties |
| `Cannot determine the main class` | No `Main-Class` manifest entry, plain GraalVM project | Set `mainClass` |
| `Runtime classpath entry … is a directory` | A reactor dependency is not packaged | `mvn package` or `mvn install` the dependency first |
| Cell fails, no obvious reason | The remote log is the record | Read the streamed output; the agent logs the full `native-image` invocation |
| Build is slower than local | Host-matching cell offloaded | Remove `forceRemote` |

## Limits worth knowing

- **One architecture matching your host is better built locally.** The plugin does this by default.
- **`jvm` build kind does not need remote workers** — it produces an architecture-neutral jar. It exists
  in the matrix for completeness.
- **Artifact digests are not reproducible between runs** because Maven embeds timestamps in jars. Two
  builds of identical source produce different digests at identical sizes. Don't use digests to verify a
  remote build matches a local one; compare architecture, linker path, and execution.
- **Spring Boot and Helidon are untested end to end.** The mechanism is in place and the configuration is
  documented; nobody has run a real project through it yet.
