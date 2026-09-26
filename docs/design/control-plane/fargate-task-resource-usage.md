# Fargate build task: measured resource usage

Measured 2026-09-25 in `eu-west-1` from four real build tasks — two builds, each running an `x86_64` and
an `arm64` cell in parallel. Sources: ECS `DescribeTasks` lifecycle timestamps, CloudWatch Container
Insights (`ECS/ContainerInsights`), and the agent's own log output.

Companion to [`lambda-resource-usage.md`](lambda-resource-usage.md), which covers the control-plane
service. Cost implications are in [`../../COST_ANALYSIS.md`](../../COST_ANALYSIS.md).

## Task lifecycle

Fargate bills from task start through termination, so the image pull is billed and the provisioning delay
before it is not.

| Phase | `x86_64` | `arm64` | From |
|---|---|---|---|
| Provisioning (`createdAt` → `pullStartedAt`) | 11 s, 13 s | 22 s, 24 s | `DescribeTasks` |
| **Image pull** (`pullStartedAt` → `pullStoppedAt`) | 9 s, 8 s | 9 s, 8 s | `DescribeTasks` |
| Execution (`startedAt` → `executionStoppedAt`) | 48 s, 43 s | 59 s, 42 s | `DescribeTasks` |
| **Total billed** (`createdAt` → `stoppedAt`) | **102 s, 96 s** | **122 s, 108 s** | `DescribeTasks` |
| `native-image` compile, of which | 45.7 s, 40.3 s | 54.8 s, 39.9 s | agent log |

All four ran on `FARGATE_SPOT`; none were interrupted.

**The image pull is 8–9 seconds, not the 60–90 s previously estimated.** The agent image is 612 MB
compressed in ECR, so that is roughly 70 MB/s — plausible for an in-region pull, and it means the pull is
a small part of the bill rather than the dominant part. Any cost model built on a slow pull is wrong by a
large factor.

**`arm64` provisions about twice as slowly**: 22–24 s against 11–13 s, consistently across both builds.
The pull and compile phases are comparable, so this is capacity allocation, not the workload. It is worth
knowing when moving to arm64 everywhere, because it lands directly in the billed window.

## Real project vs example app

Re-measured 2026-09-26 against KubeMicroVM `operator-controller`: a Quarkus 3.39.4 operator with **233
jars and a 71.9 MB runtime classpath**, against the example app's 2 jars and 0.72 MB. Two runs, both
architectures, per-task figures separated by the `TaskDefinitionFamily` dimension.

| | Example app (2 jars) | Real Quarkus (233 jars) |
|---|---|---|
| Memory peak, per task | 1179 MB | **5203–5312 MB** |
| Memory, % of 16 GiB reserved | 7% | **32%** |
| CPU peak | 4096 / 4096 | 4024–4095 / 4096 |
| Ephemeral storage | 2 GB | 1.9 GB |
| `native-image` compile | 40–55 s | 3m 6s – 5m 1s |
| Total billed per task | 96–122 s | **250–258 s** |
| Image pull | 8–9 s | 8 s |

**This settles the memory question, and the answer is: leave 16 GiB alone.** The 7% reading was an
artifact of a trivial example, exactly as suspected. A mid-size real application uses **4.5× more memory**
— 5.2 GB — and `native-image` memory scales with application size, so a larger codebase will use more
still.

Trimming to the smallest legal pairing for 4 vCPU (8 GiB) would put this project at **65% utilisation with
2.9 GB headroom**. That is too thin for a provision meant to serve arbitrary projects, and an overrun is a
task failure rather than a slowdown. The ~15% saving is not worth it.

**CPU saturates regardless of project size**, so 4 vCPU is doing real work in both cases and reducing it
would extend every build proportionally.

**Image pull is 8 s in both cases**, which follows: the pull is the agent image, not the project. Project
size affects the S3 input download instead, which sits inside the execution phase.

**arm64 provisioning is consistently slower** — 20 s against 11 s here, 22–24 s against 11–13 s
previously. Reproduced across four runs now, and it lands in the billed window.

**Compile time varied more than expected between runs** on identical inputs: 3m 37s and 5m 1s on the first
pass, 3m 7s and 3m 6s on the second. Spot capacity variance is the likely cause. Treat a single compile
timing as indicative only.

## CPU, memory and storage

From Container Insights over the task window. Note the sampling caveat below.

| Metric | Reserved | Peak used | Utilisation |
|---|---|---|---|
| CPU (vCPU units) | 4096 | **4096** | **100%** |
| Memory (MB) | 16384 | **1179** | **7%** |
| Ephemeral storage (GB) | 20 | 2 | 10% |

**CPU saturates.** `native-image` uses all four vCPU for the duration of the compile, so the 4 vCPU
request is doing real work and reducing it would lengthen the build roughly proportionally.

**Memory does not come close.** 1179 MB peak against 16 GiB reserved. Fargate bills vCPU *and* memory
independently, so the unused 15 GiB is paid for. 4 vCPU permits 8–30 GiB, so the smallest legal pairing is
4 vCPU / 8 GiB, which would cut the memory component in half — about 15% off the task rate.

**That reduction was not made, and the section above now explains why it should not be.** A real 233-jar
project uses 5.2 GB, not 1.2 GB, which would be 65% of an 8 GiB provision. The 7% figure said the
*example* was small, not that the provision was wrong — and re-measuring confirmed it.

## Sampling caveats

- **Eight tasks now**, across two projects and four runs. Enough to be confident that arm64 provisioning is
  slower and the pull is fast; still not enough for percentiles, and compile time in particular varied by
  60% between runs on identical inputs.
- **Container Insights samples at one-minute intervals** and these tasks run 96–122 s, so there are only
  two or three samples per task. A brief memory spike during compilation could fall between them, which
  makes 1179 MB a lower bound on the true peak.
- **The compile figures come from the agent's own log line** (`Finished generating '…' in Ns`), so they
  measure `native-image` itself and exclude the S3 input download and artifact upload that bracket it.
  The gap between compile time and execution time is that I/O.

## Reproducing

```bash
# Lifecycle timings for recently stopped tasks (ECS retains them about an hour).
ARNS=$(aws ecs list-tasks --cluster scaleout-build --region eu-west-1 \
  --desired-status STOPPED --query 'taskArns' --output text)
aws ecs describe-tasks --cluster scaleout-build --region eu-west-1 --tasks $ARNS \
  --query 'tasks[*].{arch:attributes[?name==`ecs.cpu-architecture`].value|[0],
                     created:createdAt,pullStart:pullStartedAt,pullStop:pullStoppedAt,
                     started:startedAt,execStopped:executionStoppedAt,stopped:stoppedAt}'

# Utilisation.
aws cloudwatch get-metric-statistics --namespace ECS/ContainerInsights \
  --metric-name MemoryUtilized --dimensions Name=ClusterName,Value=scaleout-build \
  --region eu-west-1 --start-time <iso> --end-time <iso> --period 60 --statistics Maximum

# Compile time.
aws logs filter-log-events --log-group-name /scaleout-build/build-agent --region eu-west-1 \
  --start-time <epoch-ms> --output json | grep -o "Finished generating '[^']*' in [0-9.]*s"
```
