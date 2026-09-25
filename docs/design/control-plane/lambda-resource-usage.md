# Control plane Lambda: measured resource usage

Measured 2026-09-25 in `eu-west-1`, account `864899852480`, from the `REPORT` lines Lambda writes for
every invocation. Both figures come from real end-to-end builds of
`docs/examples/scaleout-build-example-app` (two matrix cells each), not from synthetic load.

Reproduce with `./scripts/lambda-usage.sh [minutes]`.

Companion documents: [`fargate-task-resource-usage.md`](fargate-task-resource-usage.md) measures the build
tasks this service launches; [`../../COST_ANALYSIS.md`](../../COST_ANALYSIS.md) turns both into per-build
cost.

## Measurements

Two rounds. The first sized both functions generously before anything had been measured; the second
reduced them against the observed figures, which is the configuration now in `ControlPlaneInfraStack`.

### Current sizing (384 MB JVM / 256 MB native)

| | JVM 384 MB arm64 | Native 256 MB x86_64 |
|---|---|---|
| Invocations / cold starts sampled | 13 / 2 | 10 / 2 |
| Max memory used | median 206 MB, **peak 237 MB (62% of 384)** | median 102 MB, **peak 145 MB (57% of 256)** |
| Duration, excluding log streams | median 214 ms, max 6045 ms | median 88 ms, max 3775 ms |
| Cold start (init) | median 2345 ms (2344–2347) | median 441 ms (414–468) |
| OOM kills | 0 | 0 |

### What the reduction cost

| | 1024 → 384 MB (JVM) | 512 → 256 MB (native) |
|---|---|---|
| Peak memory | 226 → 237 MB | 145 → 145 MB (unchanged) |
| Duration (excl. streams) | 82 → 214 ms (**2.6× slower**) | 46 → 88 ms (**1.9× slower**) |
| Cold start | 2064 → 2345 ms (14% slower) | 464 → 441 ms (**unchanged**) |

Two results worth noting.

**Native cold start did not move.** Halving its memory halved its vCPU, and init was unaffected — because
a native binary has no JIT to warm, so init is dominated by I/O and setup rather than compute. The JVM's
did move, for the mirror-image reason: its 2.3 s init is largely class loading and JIT, which is exactly
what less CPU slows down.

**Short requests are close to cost-neutral.** Lambda bills GB-seconds, so halving memory while duration
roughly doubles leaves the product almost unchanged: native measured 0.022 GB-s per short request at
256 MB against 0.023 GB-s at 512 MB. The saving does not come from short requests at all — it comes from
the SSE log stream, which holds a connection for the build's wall-clock duration regardless of how much
CPU it has. That line is halved outright, and per `../../COST_ANALYSIS.md` it was the second-largest
per-build cost before the change.

So the resize is justified by the shape of the workload, not by the utilisation percentages. Trimming
memory on a purely CPU-bound function would have saved nothing.

### On architecture

**arm64 is the intended production target for both modes.** Native currently reports x86_64 only because a
native image cannot be cross-compiled and the workstation building it is x86_64 — that is a property of
the test setup, not a deployment decision. `deploy.yml` already selects an `ubuntu-24.04-arm` runner for
native+arm64, so reaching it is a CI matter rather than a code change.

Treat the x86_64 column below as a measurement artifact. For cost purposes use the arm64 rate; see
[`../../COST_ANALYSIS.md`](../../COST_ANALYSIS.md) §4.

The JVM runs arm64 and native runs x86_64, so the two columns are not a clean size comparison.

That also has a billing consequence the cost document now reflects: Lambda charges x86_64 at
$0.0000166667/GB-s against arm64's $0.0000133334 in `eu-west-1` — 25% more — so native's saving is
smaller than its memory reduction implies. Building on an arm64 runner would recover it.

On instruction-set differences specifically (arm64 has NEON/SVE rather than AVX): nothing here isolates
such an effect, and it is unlikely to be visible in this workload. The service does JSON serialization,
HTTP, and AWS SDK calls — not the dense numeric work that vectorises. The measured slowdowns above are
fully accounted for by vCPU proportionality, which is the simpler explanation and does not require
invoking the ISA. Where SIMD would plausibly matter is `native-image` compilation on the Fargate agents,
which is a different workload on different hardware and is not measured here.

## First round, generous sizing

| | JVM | Native |
|---|---|---|
| Runtime | `java25` | `provided.al2023` |
| Architecture | `arm64` | `x86_64` |
| Configured memory | 1024 MB | 512 MB |
| Deployment artifact | 35.8 MB zip | 26.9 MB zip |
| Invocations sampled | 45 | 25 |
| Cold starts sampled | 6 | 6 |
| **Max memory used** | median 202 MB, p95 226 MB, peak 226 MB | median 93 MB, p95 145 MB, peak 145 MB |
| **Duration** | median 88 ms | median 47 ms |
| Duration, excluding log streams | median 82 ms, max 4990 ms (n=42) | median 46 ms, max 3476 ms (n=24) |
| **Cold start (init)** | median 2064 ms, range 1811–2364 | median 464 ms, range 446–482 |
| Billed total for the sample | 364.2 s | 135.4 s |

Cold start is **4.5× faster** native. Peak memory is **145 MB against 226 MB**.

## Reading these numbers

**There is no CPU metric, and that is not an omission.** Lambda allocates vCPU in proportion to
configured memory — roughly one full vCPU at 1769 MB — and bills on GB-seconds. So duration at a fixed
memory size *is* the CPU signal, and a "CPU utilisation" figure would be invented. That is why
`lambda-usage.sh` reports memory and duration together and nothing else.

**The comparison is confounded, and conservatively so.** Native ran at 512 MB and JVM at 1024 MB, so
native had roughly *half* the vCPU share and was still faster on both duration and cold start. Treat the
native numbers as a lower bound on its advantage rather than a like-for-like measurement. Architecture
differs too (`arm64` vs `x86_64`), for the reason in
[`HANDOVER.md`](HANDOVER.md): a JVM zip is architecture-neutral so arm64 is a free price choice, while a
native binary is architecture-specific and takes whatever built it.

**The long-duration outliers are the SSE log stream, not pathology.** `LogStreamResource` holds a
connection for up to its 780-second budget while a build runs, so single invocations of 123 s and 135 s
appear in each sample. The "excluding log streams" row (under 10 s) is the figure to use when reasoning
about request latency; the raw median is the one to use when reasoning about cost.

**Sample sizes are small.** 45 and 25 invocations, 6 cold starts each. The cold-start gap is large enough
to be unambiguous at that size; the duration medians are not far apart in absolute terms and should not be
over-read.

## Why the reduction was measured rather than assumed

The original 1024/512 figures were over-provisioned on paper — 22% and 28% of configured — but the
headroom percentage is the wrong thing to optimise on Lambda, because memory and vCPU move together. The
measurements above are what justify the change: the saving is real only because the dominant cost is a
wall-clock connection hold, and the latency cost is real and was quantified rather than predicted.

The JVM at 384 MB is the tighter of the two: 237 MB peak against 384 MB provisioned. An overrun is an OOM
kill, not a slowdown, so re-check with `scripts/lambda-usage.sh` after anything that grows the working
set — notably a build with a large classpath, which this sample does not exercise. `verify-synth.sh` asserts the per-mode split so it cannot
drift silently — and that assertion caught a real bug when written, where the JVM memory bump had been
applied to the reaper instead of the service.

## Practical consequences

- **Native is the better production choice on these numbers**: 5.3× faster cold start at current sizing
  (2345 ms vs 441 ms), a third less memory,
  smaller artifact. Its cost is build time — roughly 120–170 s for the native image versus a few seconds
  for the JVM zip — and a harder failure mode, since four native-only defects had to be fixed before it
  worked at all (see `#35`).
- **JVM remains the default** in the CDK stack, because it needs no GraalVM toolchain, so a first deploy
  works from a plain `mvn package`. That trade is about onboarding, not performance.
- **Both are now sized close to their measured peaks** (62% and 57%), so there is little left to trim and
  the next change should go the other way if anything grows. The obvious risk is a build with many more
  inputs: `missingDigests` and the presigning loop scale with input count, and nothing here exercises a
  large classpath. Re-measure against a realistic project before assuming these figures hold.

## Caveats worth keeping

- The reaper Lambda is not measured here. It is 512 MB, `java25`, `arm64`, invoked once a minute by
  EventBridge, and exits immediately when there is nothing to reap.
- `Max Memory Used` is reported per execution environment, not per request, so a warm container that has
  served several requests reports its high-water mark. That makes the peak figure the right one for
  sizing and the median slightly optimistic.
- These numbers are from the example app, whose classpath is two jars. A real project's build will upload
  more inputs and hold more in memory while presigning.
