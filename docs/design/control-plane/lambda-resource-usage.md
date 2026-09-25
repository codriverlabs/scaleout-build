# Control plane Lambda: measured resource usage

Measured 2026-09-25 in `eu-west-1`, account `864899852480`, from the `REPORT` lines Lambda writes for
every invocation. Both figures come from real end-to-end builds of
`docs/examples/scaleout-build-example-app` (two matrix cells each), not from synthetic load.

Reproduce with `./scripts/lambda-usage.sh [minutes]`.

## Measurements

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

## Why memory has not been reduced

Both configurations are over-provisioned on paper — JVM peaks at 22% of 1024 MB, native at 28% of 512 MB —
and both are left as they are deliberately.

Reducing memory also reduces vCPU, so it lengthens exactly the durations above. At 512 MB a function gets
under a third of a vCPU; halving again would slow the JVM's cold start in particular, and a Function URL's
first request is where that is most visible. Any reduction should be measured, not assumed to be free.

The JVM figure of 1024 MB exists for that reason: it is not sized for the 226 MB of heap it actually uses,
it is sized to buy vCPU for JIT during init. `verify-synth.sh` asserts the 1024/512 split per mode so it
cannot drift silently — and that assertion caught a real bug when written, where the JVM bump had been
applied to the reaper instead of the service.

## Practical consequences

- **Native is the better production choice on these numbers**: 4.5× faster cold start, ~35% less memory,
  smaller artifact. Its cost is build time — roughly 120–170 s for the native image versus a few seconds
  for the JVM zip — and a harder failure mode, since four native-only defects had to be fixed before it
  worked at all (see `#35`).
- **JVM remains the default** in the CDK stack, because it needs no GraalVM toolchain, so a first deploy
  works from a plain `mvn package`. That trade is about onboarding, not performance.
- **Neither is near a memory limit**, so the service is not a candidate for memory tuning until something
  changes its working set. The obvious candidate is a build with many more inputs: `missingDigests` and
  the presigning loop scale with input count, and nothing here exercises a large classpath. Worth
  re-measuring against a realistic project rather than the example app.

## Caveats worth keeping

- The reaper Lambda is not measured here. It is 512 MB, `java25`, `arm64`, invoked once a minute by
  EventBridge, and exits immediately when there is nothing to reap.
- `Max Memory Used` is reported per execution environment, not per request, so a warm container that has
  served several requests reports its high-water mark. That makes the peak figure the right one for
  sizing and the median slightly optimistic.
- These numbers are from the example app, whose classpath is two jars. A real project's build will upload
  more inputs and hold more in memory while presigning.
