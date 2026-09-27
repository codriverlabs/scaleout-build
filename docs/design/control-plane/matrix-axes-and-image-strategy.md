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
| Absolute `/home/ubuntu/...` references | 107 |
| `-cp` entries | into `~/.m2/repository`, outside any staged directory |
| `-H:ConfigurationFileDirectories` entries | 57, into `target/graalvm-reachability-metadata/<hash>/...` (37 MB tree) |
| `-o` | absolute host path |

Those paths do not exist in the container. **This is by design on GraalVM's side** — `write-args-file`
records the command for the machine that produced it, where absolute paths are correct. The error was in
`ArgsFileDirectoryStrategy`, whose javadoc assumed *"the arguments reference it by relative path — which is
how Quarkus's equivalent directory works."* Quarkus's `native-sources` is deliberately relocatable; that is
the whole point of `-Dquarkus.native.sources-only=true`. Generalising from one to the other was our mistake.

### 1.2 The part that already works

GraalVM
[auto-discovers configuration](https://www.graalvm.org/latest/reference-manual/native-image/overview/BuildConfiguration/)
from `META-INF/native-image/` — and any subdirectory — anywhere on the classpath. Spring's AOT step populates
`target/classes/META-INF/native-image/`:

| | Measured |
|---|---|
| `reachability-metadata.json` files | **58** |
| Size | 1.9 MB |
| Libraries covered | the same 56 the argfile's `-H:` entries name — attoparser, classmate, tomcat-embed, HikariCP, jackson, logback, caffeine … |

So the AOT-generated configuration **travels with the classpath we already stage**, and the 57 absolute
directory references look redundant rather than load-bearing — they point at the downloaded repository cache
from which those 58 files were selected.

### 1.3 Remaining work, and it is small

1. Glob for `native-image-*.args`; fail if more than one matches.
2. Stage `-cp` entries through the existing per-blob content-addressed store. `DerivedClasspathStrategy`
   already does exactly this, which is where the measured 234/235 dedup comes from — so Spring Boot inherits
   the upload avoidance for free.
3. Rewrite `-cp` and `-o` to container paths. Set `-o` explicitly; a bare `-o` resolving to the working
   directory was already a bug once (#36).
4. Drop `-H:ConfigurationFileDirectories`, or rewrite it if §1.2 turns out to be wrong.

**Verification gate before believing §1.2:** build petclinic locally with the full argfile, build it remotely
without the `-H:` entries, and compare the binaries byte for byte — the same check that validated Quarkus
(13,372,680 B x86-64, 13,241,624 B aarch64, identical to the pre-migration baseline). A binary that builds
but silently lacks metadata fails at run time, which is the failure mode this project has already been bitten
by and the reason `argsFileDirectory` has no default.

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
