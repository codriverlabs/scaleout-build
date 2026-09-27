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
| **Spring Boot AOT** | **Verified end to end** — both architectures, against a control build. See below |
| **Helidon** | Same code path; untested against a Helidon project |

Spring Boot and Helidon build through `native-maven-plugin`'s `write-args-file`, whose output is designed for
the machine that produced it — absolute paths and a randomized filename. The plugin **rewrites** that argfile:
classpath jars into `lib/`, classpath *directories* staged as trees so `META-INF/native-image/` survives,
`-H:ConfigurationFileDirectories` staged and rewritten, `-o` redirected into the staging output directory.

Verified against `spring-petclinic` (Spring Boot 4.1.0), both architectures, on the deployed control plane:
565 files staged, **0 absolute paths remaining**, binaries of 210,767,112 B (`x86_64`) and 204,082,456 B
(`arm64`). The `x86_64` binary **starts AOT-processed in 0.293 s** and serves `/` and `/actuator/health`.

The rewrite was then checked against a **control build** — the unmodified argfile run through `native-image`
in a container, same Mandrel 25.0.4.1, absolute paths intact. Both binaries are exactly 210,767,112 bytes and
behave identically on every endpoint tested, so the rewrite preserves the build.

**One known upstream gap.** `/vets.html` returns 500 on
`MissingReflectionRegistrationError` for `org.thymeleaf.expression.Numbers.sequence` — **identically in the
control build**, so it is a gap between Thymeleaf's published reachability metadata and what petclinic's
pagination template exercises, not something this plugin introduces. It affects any native build of that
application.

### Using it

```
mvn -Pnative package
mvn native:write-args-file
mvn scaleout-build:build -Dscaleout-build.argsFileDirectory=target
```

The directory must contain exactly one `*.args` file. `write-args-file` uses a randomized name, so stale
files accumulate across builds — the plugin refuses to guess which is current rather than picking one.

## Troubleshooting## Troubleshooting

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
