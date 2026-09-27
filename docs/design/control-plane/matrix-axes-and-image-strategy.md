# Matrix axes, agent image strategy, and the Spring Boot blocker

Status: design. Records a measured defect that blocks the framework coverage this roadmap assumes.

## 1. The blocker, measured

Verified 2026-09-27 against [`spring-petclinic`](https://github.com/spring-projects/spring-petclinic)
(Spring Boot **4.1.0**, `native-maven-plugin` **1.1.1** inherited from the parent, single module).

`native:write-args-file` succeeds and reports:

```
[INFO] Args file written to: target/native-image-2514289111389238304.args
```

Three assumptions in `ArgsFileDirectoryStrategy` fail against that output.

### 1.1 The filename is randomized

The strategy resolves `StagingLayout.DEFAULT_ARGS_FILE_NAME` inside the configured directory.
`write-args-file` emits `native-image-<random long>.args`. So `appliesTo` passes — the directory exists —
and `plan` throws, telling the user to rename the file.

That is at least a *loud* failure with actionable advice, which was the deliberate intent of requiring the
directory rather than guessing it. But renaming is a workaround, and it does not survive the next build,
because the suffix changes.

### 1.2 The arguments are absolute paths, not relative

This is the substantive defect. The strategy's javadoc states the assumption plainly — *"on the assumption
the arguments reference it by relative path — which is how Quarkus's equivalent directory works."*

Measured in the petclinic argfile:

| | Count |
|---|---|
| Absolute `/home/ubuntu/...` path references | **107** |
| `-H:ConfigurationFileDirectories` entries | **57** |
| Size of the referenced metadata tree | **37 MB** |

Concretely, the argfile contains a `-cp` whose entries point into `~/.m2/repository` — **outside the staged
directory entirely** — an `-o` naming an absolute host path, and 57 configuration directories under
`target/graalvm-reachability-metadata/<hash>/<group>/<artifact>/<version>/`.

None of those paths exist inside the Fargate container. Staging the directory and running the argfile
verbatim cannot work.

**Why the assumption was wrong rather than unlucky.** Quarkus's `native-sources` output is *designed* to be
relocatable — that is the purpose of `-Dquarkus.native.sources-only=true`, which exists so the compile can
happen elsewhere. `native-maven-plugin`'s `write-args-file` is designed for local invocation on the machine
that produced it, so absolute paths are correct for its intended use. Generalising from one to the other was
the error.

### 1.3 Walking the directory stages the wrong things

`Files.walk(directory)` over `target/` would stage compiled classes, the repackaged fat jar, and the 37 MB
metadata tree, while still missing every `~/.m2` jar the classpath actually needs.

## 2. The fix, scoped

Rewrite the argfile rather than relay it. Each piece already exists somewhere in the codebase:

1. **Parse the argfile.** Split `-cp` on the path separator; collect `-H:ConfigurationFileDirectories`
   (comma-separated); find `-o`.
2. **Stage the classpath jars through the existing per-blob CAS.** This is exactly what
   `DerivedClasspathStrategy` already does, and it is where the 234/235 dedup comes from — so a Spring Boot
   build gets the same upload avoidance for free.
3. **Stage the referenced metadata directories**, preserving their relative shape.
4. **Emit a rewritten argfile** with container paths, and set `-o` explicitly — bare `-o` resolving to the
   working directory was already a bug once (#36).
5. **Glob for `native-image-*.args`** instead of a fixed name, and fail if more than one matches.

Not large, but a feature rather than a tweak, and it needs the same end-to-end verification Quarkus got:
both architectures, byte sizes compared, binary actually executed.

**Until it lands, Spring Boot AOT and Helidon are unsupported rather than untested.** The user guide should
say so.

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

1. **Fix `ArgsFileDirectoryStrategy`** (§2) and verify against petclinic on both architectures. Without it
   the two most common Spring frameworks are unsupported, which matters more than any new axis.
2. Mark Spring Boot AOT and Helidon unsupported in the user guide until then.
3. Add the **version axis**, with one image per version initially — simplest, and it makes the axis real.
4. Measure whether image pull has become material. If it has, evaluate SOCI against a consolidated
   multi-toolchain image.
5. Cheap flag axes (`--gc`, `-Ob`) at any point; they need no image work.
