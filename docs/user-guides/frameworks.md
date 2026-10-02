# User Guide: Framework Support

The plugin detects how your project builds; you do not choose a mode. Some frameworks need one command
first, to make them emit their `native-image` arguments instead of running the compile themselves.

The plugin detects how your project builds; you do not choose a mode. Some frameworks need one command first,
to make them emit their `native-image` arguments instead of running the compile themselves.

| Your project | Extra step | Detected by | Verified |
|---|---|---|---|
| **Plain GraalVM** | none | nothing else matched | Example app, both arches |
| **Quarkus** | build with `sources-only` (below) | `target/native-sources/` exists | 3.39.4, 233 deps, both arches |
| **Spring Boot AOT** | `mvn native:write-args-file` (below) | `argsFileDirectory` is set | Spring Boot 4.1.0, both arches |
| **Micronaut** | same as Spring Boot (`mvn native:write-args-file`) | `argsFileDirectory` is set | Staging works; compile fails on Logback/Mandrel — not our bug |
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
