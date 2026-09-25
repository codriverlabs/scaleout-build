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

**That reduction has not been made, and should not be on this evidence alone.** The example app's
classpath is two jars. `native-image` memory scales with the size of the application and its reachable
heap, and a real project with hundreds of dependencies is the case 16 GiB was chosen for. Measure a
representative build before touching it; the 7% figure says the *example* is small, not that the
provision is wrong.

## Sampling caveats

- **Four tasks.** Enough to see that arm64 provisioning is consistently slower and that the pull is fast;
  not enough for confident percentiles.
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
