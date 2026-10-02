# Matrix axes, agent image strategy, and the Spring Boot blocker

Status: design. Records a measured defect that blocks the framework coverage this roadmap assumes.

## 1. Spring Boot AOT: what is actually missing

Verified 2026-09-27 against [`spring-petclinic`](https://github.com/spring-projects/spring-petclinic)
(Spring Boot **4.1.0**, `native-maven-plugin` **1.1.1** inherited from the parent).

**State the conclusion first, because the earlier version of this document overstated it.** Spring Boot
native builds are ordinary GraalVM builds. Nothing in their output prevents building remotely. The gap is a
path-relocation gap in *this project*, and most of the hard part is already handled by GraalVM's own
classpath auto-discovery.

### 1.1 What `write-args-file` emits

`native:write-args-file` succeeds and reports:

```
[INFO] Args file written to: target/native-image-2514289111389238304.args
```

| | Measured |
|---|---|
| Filename | randomized — `native-image-<random long>.args` |
| Absolute `$HOME/...` references | 107 |
| `-cp` entries | into `~/.m2/repository`, outside any staged directory |
| `-H:ConfigurationFileDirectories` entries | 57, into `target/graalvm-reachability-metadata/<hash>/...` (37 MB tree) |
| `-o` | absolute host path |

Those paths do not exist in the container. **This is by design on GraalVM's side** — `write-args-file`
records the command for the machine that produced it, where absolute paths are correct. The error was in
`ArgsFileDirectoryStrategy`, whose javadoc assumed *"the arguments reference it by relative path — which is
how Quarkus's equivalent directory works."* Quarkus's `native-sources` is deliberately relocatable; that is
the whole point of `-Dquarkus.native.sources-only=true`. Generalising from one to the other was our mistake.

### 1.2 A hypothesis that a real build disproved

The first implementation **dropped** `-H:ConfigurationFileDirectories`, reasoning that GraalVM
[auto-discovers configuration](https://www.graalvm.org/latest/reference-manual/native-image/overview/BuildConfiguration/)
from `META-INF/native-image/` on the classpath, that Spring's AOT step populates
`target/classes/META-INF/native-image/`, and that the two said the same thing. The library sets supported it:
**56** artifacts named by the flag against **57** on the classpath — a superset, nothing missing.

It was documented as unverified, and the verification killed it. The binary compiled for 8m24s and then:

```
Invalid logger interface org.hibernate.validator.internal.util.logging.Log (implementation not found)
```

| Source | Version | Reflection entries | `Log_$logger` |
|---|---|---|---|
| `-H:ConfigurationFileDirectories` | **7.0.4.Final** | **356** | **10** |
| `META-INF/native-image/` on the classpath | 9.1.0.Final | 12 | **0** |

Two things combine. The reachability-metadata repository carries far richer metadata than a library ships
inline, and `native-maven-plugin` falls back to the newest version it holds when it has no exact match —
it logs `Configuration directory not found. Trying latest version`. So the flag can be the only place a
needed registration exists, for a *different version* of the same artifact.

**Overlapping names said nothing about overlapping content.** The directories are now staged as trees and the
flag rewritten. Corroboration that the metadata is genuinely consumed: the binary grew from 205,982,984 to
210,767,112 bytes.

### 1.3 Implemented and verified remotely

Against `spring-petclinic` (Spring Boot 4.1.0, `native-maven-plugin` 1.1.1), both architectures, via the
deployed control plane:

| | |
|---|---|
| Files staged | **565** — 403 from `target/classes`, 105 jars, 57 config directories |
| Absolute paths remaining | **0** |
| Classpath entries preserved | 106 → 106 |
| Config directories preserved | 57 → 57 |
| `x86_64` binary | `ELF 64-bit LSB executable, x86-64` — 210,767,112 B, 5m21s |
| `arm64` binary | `ELF 64-bit LSB executable, ARM aarch64` — 204,082,456 B, 8m46s |
| CAS dedup on rebuild | 2 inputs uploaded, 495 already staged |

**The binary runs.** Started AOT-processed in **0.293 s**; `GET /` and `GET /actuator/health` both 200.

#### The Thymeleaf failure is upstream, established by a control build

`GET /vets.html` returns 500 on both the remote build and a local control:

```
MissingReflectionRegistrationError: Cannot reflectively invoke method
'public java.lang.Integer[] org.thymeleaf.expression.Numbers.sequence(java.lang.Integer,java.lang.Integer)'
```

**The control.** `native-maven-plugin` has no container mode — `native:compile` requires a local GraalVM, and
this workstation has OpenJDK 25. But the agent image carries **Mandrel 25.0.4.1**, the same toolchain the
remote build used, so the control was run in that image with the **unmodified** argfile and the project and
`~/.m2` bind-mounted at their original paths, so every absolute path resolved:

```
docker run --platform linux/amd64 \
  -v /path/to/spring-petclinic:/path/to/spring-petclinic \
  -v $HOME/.m2:$HOME/.m2:ro \
  --entrypoint native-image "$AGENT_IMAGE" "@target/native-image-<id>.args" -o target/control-petclinic
```

That isolates the rewrite from the toolchain: same `native-image`, same metadata, original absolute paths, no
staging.

| | Control (original argfile, container) | Remote (rewritten argfile, Fargate) |
|---|---|---|
| Size | **210,767,112 B** | **210,767,112 B** |
| Compile | 3m22s | 5m21s |
| `GET /` | 200 | 200 |
| `GET /actuator/health` | 200 | 200 |
| `GET /vets.html` | **500** | **500** |
| Error | `MissingReflectionRegistrationError`, `Numbers.sequence` | identical |

**Conclusion: the rewrite is faithful and the Thymeleaf gap is upstream.** A plain `native-image` invocation
of the untouched argfile fails identically, so nothing the rewrite does causes it. The metadata declares
`org.thymeleaf.expression.Numbers` with `allPublicMethods: true` and GraalVM still refuses the invoke — a
gap between the published reachability metadata and what petclinic's pagination template exercises.

#### Byte-identity is not an available gate, and §1.2 was wrong to propose one

This document previously said the gate was a byte-for-byte comparison. That is not achievable: the two
binaries are **exactly the same length** and **135,362,134 bytes differ**, from the GNU build-id at byte 645
onward. `native-image` output is not bit-reproducible across runs in this configuration.

Identical length with differing content is itself informative — same structure, non-deterministic contents —
but the gate that actually settles faithfulness is **behavioural equivalence against a control built from the
unmodified inputs with the same toolchain**. That is what was run, and what future framework verification
should use.

## 3. Matrix axes

Today: `MatrixCell(BuildKind, Architecture)` — `JVM`, `NATIVE`, `NATIVE_PGO_INSTRUMENT`,
`NATIVE_PGO_OPTIMIZE` × `x86_64`, `arm64`. Up to 8 cells.

The fan-out case for the GraalVM community is more axes on that, **not** distributing `javac`. The
economics of offloading a compile only work because `native-image` is 3m06s–5m01s of saturated CPU against
~28 s of provisioning and pull; a per-module `javac` measured in seconds inverts that ratio, and Maven's
plugin model is not hermetic enough to schedule safely.

Candidate axes, in value order:

| Axis | Values | Why | Cost |
|---|---|---|---|
| **GraalVM/Mandrel version** | e.g. 3 | Library maintainers verifying reachability metadata across versions. The highest-value axis and the one with no current answer. | Needs multiple toolchains — see §4 |
| `--gc` | serial, G1 | Footprint/throughput trade-offs differ per release | Free; a flag |
| libc | glibc, musl static | Static binaries for distroless/scratch | Second base image |
| Optimization | `-Ob` quick, full | Fast feedback vs shipping build | Free; a flag |

Worked example: 3 versions × 2 architectures × 2 GC options = **12 native-image builds**. Sequentially on
one workstation that is roughly 48 minutes, and the 6 `arm64` cells are not possible at all on `x86_64`.
Fanned out: **~5 minutes wall clock, about $0.08** at measured Spot rates.

**PGO does not parallelise within an architecture.** Instrument → run workload → optimize is inherently
ordered, so those cells are sequential per architecture and only concurrent across them. That ordering is
what makes MicroVM suspend/resume interesting: no compute charge across the profiling gap
([`microvm-platform-fit.md`](microvm-platform-fit.md)).

## 4. The agent image, and where SOCI fits

Current image: **1.19 GB uncompressed, 0.61 GB in ECR**, multi-arch. Measured pull: **8–9 s**, consistently.

### SOCI today: not worth it

[Seekable OCI](https://aws.amazon.com/blogs/containers/under-the-hood-lazy-loading-container-images-with-seekable-oci-and-aws-fargate/)
lets Fargate lazily load an indexed image, starting the container before the pull completes. Since
November 2023 the index is needed only for the images you choose to lazy-load, not every container in the
task.

Against our measurements the ceiling is small:

| | Pull | Task | Pull as share |
|---|---|---|---|
| Example app | 8–9 s | 96–122 s | ~8% |
| Real Quarkus project | 8 s | 250–258 s | **~3%** |

Eliminating the pull entirely saves at most ~8 s, and SOCI does not eliminate it — it defers it. The benefit
scales with *how little of the image the workload touches*, and a `native-image` build exercises most of the
toolchain. **Adding an index-generation step to the release pipeline to chase 3% is not a good trade.**

### SOCI with a version axis: this is the pairing

The version axis forces a choice, and SOCI decides it.

| Approach | Image size | Pull cost | ECR storage |
|---|---|---|---|
| One image per version | ~0.61 GB each | full, per task | × N |
| One image, N toolchains, **no SOCI** | ~1.5–2 GB+ | full, per task — pulling 3 toolchains to use 1 | × 1 |
| One image, N toolchains, **with SOCI** | ~1.5–2 GB+ | lazy: only the toolchain that task needs | × 1 |

The third row is SOCI's actual sweet spot — a large image of which each task needs a predictable fraction —
and it is the opposite of today's situation. So the honest sequencing is: **SOCI is not justified now, and
becomes the enabling piece the moment a version axis exists.**

Unverified before committing to this: whether Fargate SOCI applies equally to `arm64` (our `arm64`
provisioning is already ~2× slower than `x86_64`, 20–24 s vs 11–13 s, so the pull is a smaller share there),
and the caveats section of the AWS blog above, which was not read in full.

## 5. Order of work

1. **Wire up `ArgsFileDirectoryStrategy`** (§1.3) and verify against petclinic on both architectures. Without it
   Spring Boot and Helidon users cannot use this plugin at all, which matters more than any new axis.
2. Until then the user guide says "not yet wired up", with the reason — not "unsupported", which would wrongly imply a defect in Spring or GraalVM.
3. Add the **version axis**, with one image per version initially — simplest, and it makes the axis real.
4. Measure whether image pull has become material. If it has, evaluate SOCI against a consolidated
   multi-toolchain image.
5. Cheap flag axes (`--gc`, `-Ob`) at any point; they need no image work.

## Appendix: Micronaut native build strategy

Investigated 2026-10-02 against Micronaut 5.2.0 (micronaut-platform 4.8.3,
native-maven-plugin 1.1.12).

### Key difference from Spring Boot

Micronaut's argfile when generated inside a container at `/project` looks like:

```
-cp /project/target/classes:/root/.m2/repository/...
-o /project/target/mn-test
-H:ConfigurationFileDirectories=...
```

**No host absolute paths.** The classpath uses the container working directory, not
`/home/ubuntu/...`. This happens because `write-args-file` in native-maven-plugin 1.1.12
requires `native-image` to be present (unlike 1.1.1 used by Spring Boot 4.1.0), so it can
only be run inside an environment that has GraalVM — i.e. inside our agent container.

### Possible strategy: agent-side write-args-file

For Micronaut, the plugin could:
1. Stage compiled `target/classes`, the POM and resolved jars
2. Agent runs `mvn native:write-args-file` inside the container at a known path (e.g. `/build`)
3. The generated argfile already has correct container-relative paths
4. Agent runs `native-image @argfile`

This avoids path rewriting entirely and is cleaner than the Spring Boot approach.
The catch: the agent image currently has no `mvn`. Adding a Maven wrapper or Maven
binary to the agent image is the enabler.

### Alternative: derive from the staged classpath

The `DerivedClasspathStrategy` already works for plain GraalVM projects. For Micronaut,
which does compile-time DI and generates reflection metadata into `target/classes/META-INF`,
derived mode may produce a working binary without `write-args-file` — worth testing first
before adding Maven to the agent.

### Recommended first step

Test `DerivedClasspathStrategy` (i.e. no `argsFileDirectory`, no native-image locally)
against a Micronaut project. Micronaut's compile-time DI means the reflection config
is already in `target/classes` — exactly the path that derived mode stages. This may
work without any new infrastructure.

### Derived mode won't work for Micronaut

Tested 2026-10-02. `mvn package -Pnative` produces:

- `target/graalvm-reachability-metadata/` — third-party metadata (hundreds of files)
- `target/native/generated/` — Micronaut-generated config

**Neither location is `target/classes/META-INF/native-image/`**. There is no classpath-discoverable metadata.
Derived mode stages `target/classes` plus the jars, and GraalVM auto-discovers config from
`META-INF/native-image/` on the classpath. Since Micronaut places its config elsewhere, derived mode would
compile but produce a binary that fails at runtime — the exact failure mode `DerivedClasspathStrategy`'s
javadoc warns about. **Derived mode is ruled out for Micronaut.**

The only viable paths are:

1. **`write-args-file` path** (same as Spring Boot) but Micronaut's plugin version 1.1.12 requires
   `native-image` to be locally present, so the user must have GraalVM or run the step inside a container.
   If GraalVM is available, the user can run `mvn native:write-args-file` and then use
   `argsFileDirectory=target`. The path relocation logic in `ArgsFileDirectoryStrategy` should handle it since
   the argfile structure is the same as Spring Boot. **Not yet tested.**

2. **Agent-side `write-args-file`**: add Maven to the agent image, run `mvn native:write-args-file` at a
   known path inside the container, get clean container-relative paths. **Not yet implemented.**

Both paths need someone with a real Micronaut application to validate.
