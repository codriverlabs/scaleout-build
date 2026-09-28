# GraalVM version as a matrix axis

Status: design. Not implemented. Prerequisite work identified in §4 is the part that can corrupt silently.

## 1. Why

The matrix is `MatrixCell(BuildKind, Architecture)` — four build kinds × two architectures, up to eight
cells. The highest-value axis missing from it is **GraalVM/Mandrel version**, for one audience in particular:
a library maintainer who needs to know their reachability metadata is correct across the versions their
consumers actually use.

Nothing in the Maven ecosystem answers that today. `native-maven-plugin` builds with whatever `native-image`
is on the local `PATH`, one version at a time.

Worked example — 3 versions × 2 architectures × 2 GC options = **12 native-image builds**:

| | Sequential, one workstation | Fanned out |
|---|---|---|
| Wall clock | ~48 min | **~5 min** |
| `arm64` cells | **impossible** on an `x86_64` host | fine |
| Cost | — | ~$0.08 at measured Spot rates |

### The Spring Boot verification sharpened the argument

Verifying Spring Boot AOT exposed that `native-maven-plugin` resolves reachability metadata **per artifact,
not per GraalVM version**, and falls back to the newest it holds when there is no exact match — it logs
`Configuration directory not found. Trying latest version`. That is how a project depending on
hibernate-validator 9.1.0.Final ended up compiled against 7.0.4.Final metadata, and why dropping
`-H:ConfigurationFileDirectories` produced a binary that started and then failed on a missing
`Log_$logger` registration.

A version matrix **surfaces** that class of mismatch instead of hiding it. That is the feature, not a
side effect.

## 2. What already supports this

Tracing the code before designing found three things already in place, which changes the cost of the axis
substantially.

| Mechanism | State |
|---|---|
| `BuildSpec.nativeImageCommand` | **Already on the wire.** Selecting a different `native-image` binary needs no new transport. |
| `NativeImageBuildExecutor` | **Already runs in the agent** — it is what logs the remote build — and reads `environment.nativeImageCommand()`. The plumbing reaches the worker. |
| `TaskDefinitionRegistrar.registerIfChanged` | **Registers task definitions dynamically**, keyed `family = (buildKind, architecture)` with a config-hash dedup that skips re-registration when nothing changed. Not pre-provisioned by CDK. |

## 3. Two shapes, and the registrar decides between them

| | One image, N toolchains | N images, one per version |
|---|---|---|
| Task definition family | unchanged | needs a version component |
| `agentImageUri` | unchanged | must vary per cell → `EcsClusterSettings`, `TaskDefinitionRegistrar`, CDK |
| Infra change | **none** | registrar + settings + infra stack |
| Image size | ~1.5–2 GB uncompressed | 0.61 GB each in ECR, × N |
| Pull cost | whole image per task, of which one toolchain is used | only what that cell needs |

**Recommendation: one image, N toolchains.** It needs zero changes to the registrar, the task definitions, or
CDK — the version becomes a different command path inside the same container, carried by a field that already
exists.

The cost lands entirely on image size, which is also where
[SOCI becomes worthwhile](matrix-axes-and-image-strategy.md#4-the-agent-image-and-where-soci-fits): a large
image of which each task needs a predictable fraction is SOCI's actual sweet spot, and the opposite of
today's single-toolchain situation. Sequence it that way — add the axis with one image, measure whether pull
time has become material, then evaluate SOCI.

## 4. The change that can corrupt silently

`StagingLayout.stagingPath(buildId, buildKind, architecture)` has **no version component**. Two cells
differing only by GraalVM version would resolve to the same S3 prefix and overwrite each other's inputs and
outputs.

That is not a clean failure. It is two builds interleaving in one directory, and the symptom would be a
binary built from a mixture — or an artifact collected from the wrong cell. `StagingLayout` is shared by the
client and the agent, so both must agree on the new shape or the agent writes where the client does not look.

**Do this change first, alone, with tests, before any other part of the axis.** Everything else in §5 fails
loudly when wrong; this one does not.

## 5. The rest of the work

1. **`StagingLayout`** — add the version to the path. §4. On its own.
2. **`MatrixCell`** — gains a dimension. Touches matrix expansion, the local/remote split, log stream naming,
   and every `Map` keyed by cell.
3. **`BuildSpec`** — add `List<String> graalvmVersions`. **Additive and backward compatible**: absent
   deserialises to `null` and falls back to the image default, so an old client keeps working. This is
   deliberately unlike adding a `CellState` enum value, which breaks old clients on deserialisation.
4. **Version → command mapping.** The agent must turn `25.0.4.1` into a path such as
   `/opt/mandrel-25.0.4.1/bin/native-image`. Decide whether the client sends the version (agent maps it) or
   the path (client must know the image layout). **Prefer the version** — the path is an internal detail of an
   image the client should not have to track.
5. **Artifact classifiers.** Today `native-linux-arm64-my-app`. With versions, something like
   `native-linux-arm64-mandrel-25.0.4.1-my-app`. User-visible; needs a decision rather than a default.
6. **Agent image** — install N toolchains, publish, verify each is invocable. The actual build work.

## 6. A semantic question to settle before implementing

A version matrix serves two different intentions, and they want **opposite** failure policies:

| Intention | Wanted behaviour |
|---|---|
| Library maintainer checking compatibility | A **report** — "24 ✓, 25 ✗, 26 ✗" — with a non-zero exit only if *all* fail, or perhaps never |
| Release build producing shippable binaries | **Fail fast** — any cell failing fails the build |

Today any cell failure fails the build, which is right for the second and useless for the first: the
compatibility answer *is* the set of failures, and discarding it on the first one throws away the result.

This is a user-facing decision, not an implementation detail. The likely answer is a policy parameter
(`-Dscaleout-build.cellFailurePolicy=fail-fast|report`), but it should be chosen deliberately, and it changes
what the goal prints and what it exits with.

## 7. Cheaper axes available first

`--gc` (serial, G1) and `-Ob` (quick build) are pure `native-image` flags — no image work, no staging change,
no new toolchain. If the goal is to demonstrate the fan-out value before committing to a multi-toolchain
image, those are the cheap way to widen the matrix.

They still need §4, because any new axis collides in S3 without it.
