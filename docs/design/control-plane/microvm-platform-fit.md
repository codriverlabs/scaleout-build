# Running the build agent on MicroVM platforms

Whether the `native-image` workload fits AWS Lambda MicroVMs or Amazon Bedrock AgentCore Runtime, checked
against documented quotas rather than assumed. Measured requirements come from
[`fargate-task-resource-usage.md`](fargate-task-resource-usage.md).

## What the agent needs

Per cell, measured on a 233-jar Quarkus application:

| | |
|---|---|
| Peak RSS | 5.3–5.6 GB |
| CPU | saturates 4 vCPU (load 3.1–3.7) |
| Compile time at 4 vCPU | ~3 min |
| Container image | 1.19 GB uncompressed, 0.61 GB in ECR |
| Disk | ~2 GB |
| Architectures needed | **both** `x86_64` and `arm64` |

That last row is the constraint everything else hangs off: GraalVM cannot cross-compile, so an `x86_64`
binary must be produced on `x86_64` hardware. It is the entire reason this project exists.

## AWS Lambda MicroVMs — cannot serve the x86_64 cell

Sizes go to 16 vCPU / 32 GB ([Lambda
quotas](https://docs.aws.amazon.com/lambda/latest/dg/gettingstarted-limits.html), inferred from the
per-MicroVM throughput table: *"40 (4 vCPU / 8 GB), 160 (16 vCPU / 32 GB)"*), and execution runs to 8
hours. Both comfortably exceed what a build needs.

**But the same page states: "Lambda MicroVMs support the ARM64 (AWS Graviton) architecture."** ARM64 only.

Combined with no cross-compilation, that rules Lambda MicroVMs out for the `x86_64` cell entirely — not as
a tuning problem, but as an impossibility. It remains a candidate for the `arm64` cell.

A split deployment (arm64 on MicroVMs, x86_64 elsewhere) is possible but buys little: it doubles the
operational surface to move one of two cells.

## AgentCore Runtime, microVM compute — fits memory, halves CPU

From [AgentCore
quotas](https://docs.aws.amazon.com/bedrock-agentcore/latest/devguide/bedrock-agentcore-limits.html), both
marked not adjustable:

| Limit | Value | Against our requirement |
|---|---|---|
| Maximum hardware allocation per session | **2 vCPU / 8 GB** | memory fits at ~70%; **CPU is half** |
| Maximum Docker image size | **2 GB** | 1.19 GB fits, ~800 MB spare |

**Memory fits, and the fit is tight by coincidence rather than design.** Peak RSS of 5.6 GB against 8 GB is
about 70%. The 8 GiB default this project just adopted happens to be exactly the AgentCore ceiling, so no
further slimming is available there — and none is possible, since peak RSS is dominated by native memory
and the image heap rather than the Java heap, so capping the heap does not move it.

**CPU is the real cost.** `native-image` saturates 4 vCPU, so 2 vCPU roughly doubles compile time: ~3 min
becomes ~6 min per cell. Whether that matters is a product decision, not a technical obstacle.

**The image fits but the headroom is not ours.** 1.19 GB of the 2 GB limit, of which the Mandrel builder
base is 1.18 GB — our additions are about 10 MB. So headroom is entirely a function of upstream base image
growth, and a future Mandrel release could breach the limit without any change on our side. Worth a check
in CI if this platform is adopted; `scripts/verify-agent-image.sh` is the natural place.

## AgentCore Runtime, Instances compute — the best technical fit

The [FAQ](https://aws.amazon.com/bedrock/agentcore/faqs/) describes Instances as *"AWS-managed Amazon EC2
instances that run in your own AWS account, for specialized workloads"*, with a broad choice of instance
types and sessions up to 14 days. Billing is the EC2 On-Demand price plus a management fee.

No 2 vCPU / 8 GB ceiling: you pick the instance, so the 4 vCPU the compile actually wants is available, and
both architectures are available since you choose the instance family. For a CPU-bound batch workload this
is the closer match of the two AgentCore compute types.

## Summary

| Platform | Memory | CPU | Both architectures | Verdict |
|---|---|---|---|---|
| Fargate (today) | ✅ 8 GiB | ✅ 4 vCPU | ✅ | works, measured |
| Lambda MicroVMs | ✅ to 32 GB | ✅ to 16 vCPU | ❌ **ARM64 only** | **x86_64 impossible** |
| AgentCore microVMs | ✅ 8 GB, at 70% | ⚠️ 2 vCPU, ~2× slower | ✅ | workable, CPU-constrained |
| AgentCore Instances | ✅ your choice | ✅ your choice | ✅ | best technical fit |

**Nothing here has been run.** These are documented quotas checked against measured requirements; no build
has executed on either platform. The conclusions about fit are sound, but the same was true of the Quarkus
support before a real project found six defects in it.

## If AgentCore microVMs are chosen anyway

The 8 GiB memory default already matches the ceiling, so no change is needed there. Two things would:

- **Expect ~6 min per cell rather than ~3.** The plugin's `timeoutMinutes` default comes from server
  policy; a deployment on 2 vCPU should raise it.
- **Add an image-size assertion.** 1.19 GB against a 2 GB hard limit, with 99% of it upstream, is the kind
  of headroom that disappears without warning.
