# Cost Analysis: Offloading a Build to ECS Fargate vs. Building Locally vs. EC2

This document estimates what one `native-image` build actually costs on the current code path —
Fargate Spot task plus the SSE-streaming control-plane Lambda — and compares it against building
locally and against EC2 (on-demand, and with Compute Savings Plans). It is the cost companion to
`docs/PURE_ECS_ALTERNATIVE.md` (which compares *orchestration* options) and
`docs/STAGING_ALTERNATIVES.md` (which compares *staging* options).

Scope: a single build cell, `native` build kind, ARM64, 5 minutes of `native-image` compilation,
in `eu-west-1` (the region this stack is deployed to per
`scaleout-build-control-plane-infra/cdk-outputs.json`).

## 1. Bottom line

**For a single architecture that matches the host, offloading is strictly worse.**
`BuildMojo.splitLocalAndRemote` already runs host-matching cells in-process via
`NativeImageBuildExecutor`, with no AWS calls at all. The local build's marginal cost is $0 and it
skips both the container image pull and the S3 staging round-trip. Reaching the remote path for a
host-matching cell requires `scaleout-build.forceRemote`.

The offload only pays for the architecture you don't own hardware for (GraalVM cannot
cross-compile) and for running matrix cells concurrently.

**The strongest case is many isolated agents.** Isolation removes the ability to amortise, so every agent
in its own MicroVM must be sized for its peak — and a `native-image` build needs 5.3–5.6 GB. An agent that
offloads needs a fraction of that, which moves a **capacity ceiling**, not just a bill: 200 concurrent
agents within Lambda MicroVM's 400 GB account quota instead of 50, a 4× increase no budget can buy. Plus 4×
less memory billed across a session that spends most of its time idle. See §8.

**Against an always-on build box, the scale-out architecture wins by two orders of magnitude at ordinary
volumes, on plain on-demand rates with no commitment.** A two-architecture setup of always-on machines is
about $503/month before a single build; 100 builds a month through a small `t4g.large` client plus Fargate
is **$2.01**. The always-on box only overtakes at roughly 600-800 builds a day, sustained. Full working
in §7 — and note that a single x86_64 box cannot produce the arm64 binary at all, so the comparison is
also one architecture against two.

**The original non-obvious finding — that the SSE Lambda cost about as much as the Fargate compute it
watched — no longer holds, and it is worth saying why it was wrong.** It rested on a 1024 MB function and
an estimated 400-second build. Measured, the build is ~100 s per cell and the function is 256 MB native,
so the relay is **$0.0015 of a $0.0127 real-project build, about 12%**, against 59% for the two Fargate
cells. §5 is
retained for its sizing levers but its premise is superseded by §4.

**~~The live improvable line is Fargate memory~~ — resolved.** The task reserved 16 GiB and peaked at
1179 MB on the example app, which looked like obvious waste. Measuring a real 233-jar project showed 5.2 GB
instead, so the task is now **8 GiB** and there is little left to trim. That reversal is the most useful
thing in this document: sizing against a trivial workload would have set up a failure on somebody's large
project. See [`fargate-task-resource-usage.md`](design/control-plane/fargate-task-resource-usage.md).

## 2. What the code pins down

Every figure below follows from configuration that is in the repository, not from assumptions about
how the system might be deployed:

| Parameter | Value | Source |
|---|---|---|
| Task size | **4 vCPU / 8 GiB** | `BuildMojo` `requestedCpu=4096`, `requestedMemory=8192`; server default `8192` |
| Capacity provider | `FARGATE_SPOT` preferred, `FARGATE` fallback | `EcsTaskLauncher` |
| Ephemeral storage | 20 GiB included, unbilled | `requestedEphemeralStorageGiB=0` — ECS rejects an explicit size below 21, so omitting it is how you get the free allowance |
| Networking | public subnets, `natGateways(0)`, `assignPublicIp` | `ControlPlaneInfraStack` — one public IPv4 per task, no NAT gateway |
| SSE Lambda | **384 MB** JVM / **256 MB** native, held open for the whole build | `memorySize(jvmMode ? 384 : 256)` — sized against measured usage, see [`lambda-resource-usage.md`](design/control-plane/lambda-resource-usage.md) |
| SSE poll cadence | 3 s (`FilterLogEvents` + `refreshFromEcs` per tick) | `LogStreamResource.POLL_INTERVAL` |
| SSE handover | 780 s, then client reconnects | `LogStreamResource.STREAM_BUDGET` |
| Client heartbeat | every 30 s | `ControlPlaneConfig.heartbeatIntervalSeconds` |
| Reaper | 512 MB ARM64, `rate(1 minute)`, always on | `ReaperSchedule` |
| DynamoDB | `PAY_PER_REQUEST` | `BuildsTable` |

The SSE Lambda was originally 1024 MB JVM / 512 MB native, chosen as headroom before anything had been
measured. Both were then sized against real usage: peak 237 MB observed in JVM mode against 384 MB
provisioned, and 145 MB native against 256 MB. Figures and method in
[`lambda-resource-usage.md`](design/control-plane/lambda-resource-usage.md).

**Why reducing memory actually saves money here, when usually it would not.** Memory and vCPU are
proportional on Lambda, so for CPU-bound work a smaller function simply runs longer and the GB-seconds
barely move — measured, native at 256 MB used 0.022 GB-s per short request against 0.023 GB-s at 512 MB,
which is noise. The saving comes entirely from the SSE relay, which is *wall-clock* bound rather than
CPU bound: it holds a connection for the duration of the build no matter how much CPU it has. Halving its
memory halves that line item outright, and it is the second-largest cost in the breakdown below.

The cost of that saving is steady-state latency, and it is not small. Measured: JVM short-request duration
went from 82 ms at 1024 MB to 214 ms at 384 MB, and native from 46 ms to 88 ms. Cold start behaved
differently between the two — JVM init worsened 2064 ms to 2345 ms, while native init did not move at all
(464 ms to 441 ms), which is what you would expect when there is no JIT to warm and init is dominated by
I/O rather than compute.

## 3. Rates

All rates `eu-west-1`, retrieved from the AWS Price List API and from the SavingsPlans
`DescribeSavingsPlansOfferingRates` API (Compute Savings Plan, No Upfront), September 2026.
Re-verify before reusing — AWS republishes the price list continuously.

### Fargate, 4 vCPU ARM64

Per-unit rates are the same at any size; the task rate depends on the memory paired with the vCPU. The
**8 GiB** row is the current default; 16 GiB is retained because earlier sections were computed with it.

| Task size | arm64 on-demand | x86_64 on-demand |
|---|---|---|
| **4 vCPU / 8 GiB** | **$0.15800/h** | **$0.19748/h** |
| 4 vCPU / 16 GiB | $0.18648/h | $0.23304/h |

### Fargate per-unit, 4 vCPU / 16 GiB ARM64

| Pricing | Per vCPU-h | Per GB-h | Task rate | vs. on-demand |
|---|---|---|---|---|
| On-demand | $0.03238 | $0.00356 | **$0.18648/h** | — |
| Compute SP, 1 yr | $0.02550 | $0.00280 | $0.14680/h | −21.3% |
| Compute SP, 3 yr | $0.01749 | $0.00192 | $0.10068/h | −46.0% |
| **Spot** (assumed −70%) | — | — | ~$0.05594/h | −70% |
| **Spot** (assumed −60%) | — | — | ~$0.07459/h | −60% |

Fargate Spot pricing is **not published in the Price List API** and is not a Savings-Plan-eligible
usage type, so the Spot rate above is an assumption from AWS's "up to 70%" claim, not a retrieved
figure. Everything else in this table was retrieved.

### Lambda, ARM64

| Pricing | Per GB-second |
|---|---|
| On-demand | $0.0000133334 |
| Compute SP (1 yr and 3 yr are identical) | $0.0000117 |

### EC2, Linux/shared, `eu-west-1`

| Instance | On-demand | Compute SP 1 yr | Compute SP 3 yr |
|---|---|---|---|
| `m7g.xlarge` (4 vCPU / 16 GiB, ARM) | $0.1819/h | $0.1381/h (−24%) | $0.0956/h (−47%) |
| `m7i.xlarge` (4 vCPU / 16 GiB, x86) | $0.2247/h | $0.17056/h (−24%) | $0.11806/h (−47%) |


## 4. Per-build cost, measured

The section that follows was written from an estimated 400-second billed window and a single task. Both
assumptions are now measured and both were wrong in ways that matter. Figures and method in
[`design/control-plane/fargate-task-resource-usage.md`](design/control-plane/fargate-task-resource-usage.md).

**A build runs two tasks, not one.** The default matrix compiles `x86_64` and `arm64` concurrently, so
Fargate cost is the *sum of both cells*. The SSE relay is not doubled: one stream serves both cells with
per-cell watermarks, which is why `LogEvent.nextSince` is a map rather than a scalar.

**The billed window is ~100 s, not 400 s.** Measured `createdAt` → `stoppedAt`: 96 s and 102 s for
`x86_64`, 108 s and 122 s for `arm64`. The estimate assumed a 60–90 s image pull; the actual pull is
**8–9 s** for a 612 MB image, in-region.

### Verified rates, `eu-west-1` (AWS Price List API, 2026-09-25)

| | vCPU-hour | GB-hour | Lambda GB-second |
|---|---|---|---|
| ARM / Graviton | $0.03238 | $0.00356 | $0.0000133334 |
| x86_64 | $0.04048 | $0.004445 | $0.0000166667 |

x86_64 costs **25% more** than ARM on both Fargate dimensions and on Lambda. That ratio is why the
arm64 cell is cheaper than the x86_64 cell despite taking longer.

### Per build, both cells, `FARGATE_SPOT`

Two workloads, because they differ by 2.4× and neither is "typical". Task rate is **4 vCPU / 8 GiB** —
$0.15800/h arm64, $0.19748/h x86_64 — which is the current default, down from 16 GiB.

| Line item | Example app (2 jars) | Real Quarkus (233 jars) |
|---|---|---|
| Fargate `arm64` cell | $0.0015 (115 s) | $0.0034 (258 s) |
| Fargate `x86_64` cell | $0.0016 (99 s) | $0.0041 (250 s) |
| **SSE Lambda** — `native` 256 MB x86_64, *as deployed* | $0.0006 (140 s) | $0.0015 (360 s) |
| Public IPv4, two tasks | $0.0003 | $0.0007 |
| CloudWatch Logs, DynamoDB, S3 staging | ~$0.0030 | ~$0.0030 |
| **Total, Spot path** | **≈ $0.0070** | **≈ $0.0127** |
| *Fargate on-demand instead* | *$0.0105* | *$0.0250* |
| **Total, on-demand** | **≈ $0.0144** | **≈ $0.0302** |

The SSE Lambda line is priced for the **deployed** configuration, `native/x86_64` at 256 MB. The
alternatives, for the real project's 360 s stream:

| Service mode | Lambda line | Note |
|---|---|---|
| `native` 256 MB x86_64 | **$0.0015** | **deployed today** — what a local native build from an x86_64 workstation produces |
| `native` 256 MB arm64 | $0.0012 | cheapest, but needs an arm64 runner; `deploy.yml` selects one, and needs an OIDC deploy role that does not yet exist |
| `jvm` 384 MB arm64 | $0.0018 | the previous resting state; needs no GraalVM toolchain to build |

Switching to native saved about **$0.0003 per build** on a $0.013 build — roughly 2%. The real reason to
prefer it is the **cold start: measured 480–524 ms on the deployed native function against 2345 ms on the
JVM**, a latency property rather than a cost one. Going further to `native/arm64` would save another $0.0003. Figures in
[`lambda-resource-usage.md`](design/control-plane/lambda-resource-usage.md).

**The Lambda is 12% of a real build and 9% of a trivial one.** The two Fargate cells are 59% and 45%
respectively. Earlier drafts of this document had that relationship inverted — see §5.

### Lambda architecture: arm64 is the target

The service is deployed `native/x86_64` only because a native image cannot be cross-compiled and the
workstation building it is x86_64. **The intended production deployment is arm64**, which is 25% cheaper
per GB-second and is what the table above prices. Reaching it needs the native image built on an arm64
runner — `deploy.yml` already selects `ubuntu-24.04-arm` for that combination, so it is a CI concern
rather than a code change.

JVM mode is architecture-neutral bytecode and already deploys arm64.

### The memory provision: asked, answered, and the answer reversed the conclusion

Container Insights: **CPU peaks at 4096 of 4096 reserved — saturated at any project size**, so 4 vCPU is
doing real work and reducing it lengthens every build proportionally.

Memory was the open question. On the example app it peaked at 1179 MB of 16384 reserved — 7%, which looked
like obvious waste worth ~15% of the largest line item. It was deliberately **not** cut on that evidence,
because `native-image` memory scales with application size and the example has two dependencies.

Re-measuring a real 233-jar project gave **5.2 GB, 4.5× more**. The task is now **8 GiB**, the smallest
pairing Fargate allows with 4 vCPU, running at about 68% with ~2.5 GB spare. There is little left to trim,
and nothing below 8 GiB is available: peak RSS is dominated by native memory and the image heap being
constructed rather than the Java heap, so capping the builder's heap does not move it.

Declining to act on the 7% reading was therefore the right call — trimming to 8 GiB *on that basis* would
have been the same number reached for the wrong reason, and the next size down would have failed on
somebody's large project.

## 4b. Original estimate (superseded by §4)

> **Superseded by §4.** Retained for its method and sensitivity analysis. Its 400-second window and
> single-task assumption are both contradicted by measurement; the pull is 8–9 s, not 60–90 s, and a build
> runs two cells.

### Billed task window

Fargate bills from the moment the task starts pulling its image until it terminates — the pull is
not free. The agent image is the Quarkus/Mandrel builder (see `scaleout-build-agent/Dockerfile`),
which is large. So the billed window is materially longer than the compile itself:

```
image pull (~60-90s) + agent JVM start + S3 input download
  + 300s native-image + S3 artifact upload  ≈ 400s
```

**400 s is an estimate**, not a measured figure — the repository contains no recorded task
provisioning or pull latency. Sensitivity is shown below.

### Cost breakdown (400 s window)

| Line item | Cost |
|---|---|
| Fargate ARM **Spot** (−70%) | $0.0062 |
| Public IPv4 ($0.005/h) | $0.0006 |
| **SSE Lambda, 384 MB × 400 s (JVM)** | **$0.0020** |
| Heartbeats + create/status invocations | ~$0.0005 |
| CloudWatch Logs ingest, DynamoDB, S3 staging | ~$0.0030 |
| **Total, Spot path** | **≈ $0.012** |
| *(Fargate on-demand instead, i.e. Spot reclaimed or unavailable)* | *$0.0207* |
| **Total, on-demand fallback path** | **≈ $0.027** |

Plus a fixed **~$0.10–0.15/month** for the reaper firing every 60 s regardless of build activity
(43,800 invocations/month at 512 MB, plus one DynamoDB GSI query each). At 10 builds/month that
amortises to ~$0.01/build — the same order as the build itself. At 100+ builds/month it is noise.

### Sensitivity to the billed window

| Billed window | Fargate Spot | Fargate on-demand | SSE Lambda (384 MB) |
|---|---|---|---|
| 300 s (compile only, no pull) | $0.0047 | $0.0155 | $0.0015 |
| **400 s (estimate used above)** | **$0.0062** | **$0.0207** | **$0.0020** |
| 480 s (slow pull) | $0.0075 | $0.0249 | $0.0024 |

Both compute and the streaming Lambda scale linearly with the window, so shortening the image pull
improves both simultaneously. That makes agent image size a cost lever, not just a latency one.

## 5. SSE Lambda sizing levers (premise superseded by §4)

> Written when the relay was 1024 MB and the build was assumed to run 400 s, making it ~34% of the bill.
> Measured, it is **$0.0015 of $0.0127 — about 12%** — so the levers below are real but no longer the
> priority. Retained because the second one, the poll interval, has a DynamoDB consequence that is
> independent of Lambda cost.

$0.0015 of a $0.0127 build — about **12%**, down from 34% before the function was resized and the window
measured — is a Lambda holding a connection open and running a
3-second `FilterLogEvents` + `refreshFromEcs` loop. Two changes, neither of which touches the wire
contract in `scaleout-build-control-plane-api`:

1. **Deploy the control plane in native mode.** Done, and both modes have since been sized against
   measured usage: 384 MB JVM, 256 MB native. The streaming line falls from $0.0053 to $0.0020 (JVM) or
   $0.0017 (native x86_64).

   Note native's saving is smaller than its memory reduction suggests, because native currently deploys
   on **x86_64** and Lambda bills x86 at $0.0000166667/GB-s against ARM's $0.0000133334 — 25% more. A
   native image cannot be cross-compiled, so the architecture follows the build host; building on an
   arm64 runner would bring the line to $0.0013. The earlier version of this document applied the ARM
   rate to native and so understated it.
2. **Widen `LogStreamResource.POLL_INTERVAL`** — but *not* for Lambda cost. The billed duration is
   pinned to the build's wall clock whether the loop ticks every 3 s or every 10 s, so the poll
   interval does nothing to the Lambda bill. What it does set is the DynamoDB write rate: ~133
   `refreshFromEcs` cycles per build, each reconciling against ECS and persisting the build record.
   Doubling the interval halves that traffic. `LogStreamResource` already emits its own keepalive
   cadence and resumable per-cell watermarks, so a slower poll neither risks the connection nor
   loses log lines.

**Memory is the only lever that moves the Lambda line.** Because duration is fixed by the build,
memory reduction here is a rare pure linear saving with no duration penalty — the usual trap (less
memory ⇒ less CPU ⇒ longer runtime ⇒ no saving) does not apply to an I/O-bound loop that is idle
>98% of the time.

Lambda response-streaming data transfer (charged beyond the first 6 MB) is not a factor: a
5-minute `native-image` log is well under that.

## 6. Choosing a log-streaming mechanism

Given §5, the natural question is whether the SSE relay is the right mechanism at all, or whether
something cheaper should carry the live log. Four candidates, and why the relay survives.

### CloudWatch Logs Live Tail — rejected on cost

`StartLiveTail` is the API that looks purpose-built for this: genuine push (a
`LiveTailSessionUpdate` every second), no polling, and a 3-hour session cap that comfortably exceeds
the `overallTimeoutMinutes` default of 120.

It is billed at **$0.01 per session-minute** (`EU-Logs-LiveTail`, retrieved from the Price List
API). A 7-minute build therefore costs **~$0.067 in Live Tail charges alone** — roughly 4× the
entire current per-build cost, and ~12× the SSE Lambda line it would replace. Live Tail is priced
for a human debugging interactively, not for every build in a matrix.

### The constraint that forces a relay

S3 supports presigned URLs. **CloudWatch Logs has no presigned equivalent.** That asymmetry is
precisely why `LogStreamResource` exists: staging can hand the client a signed URL and keep the
control plane out of the data path entirely, but logs cannot, so something holding credentials must
relay them. This is forced by the AWS surface, not a design oversight.

### Scoped STS credentials to the client — rejected on posture

The control plane could mint short-lived `sts:AssumeRole` credentials with a session policy scoped
to `logs:FilterLogEvents` on exactly that build's log streams, and let the plugin tail CloudWatch
itself using `CloudWatchLogTailer` (which `BuildMojo` already imports for the direct-ECS path, so
the client-side capability exists today).

Rejected because it regresses the client's security posture. The current split is deliberate: the
**agent** uses its task role for plain S3 calls (see `S3Io`), while the **client** holds no AWS
credentials at all and works purely from presigned URLs. Putting real STS session credentials onto
developer laptops and CI runners is a categorical change, not a tightening — scoped and short-lived
beats broad, but "no credentials" beats both.

### Agent ships its own logs to S3 — rejected on failure modes

The agent already has task-role S3 write access, so it could flush log chunks to the staging bucket
and let the client poll a presigned `GET` with `Range` from its own byte offset. This keeps the
client credential-free, removes the held-open Lambda, has no 900 s ceiling, and costs about
$0.001/build. It would also sidestep the cross-cell interleaving problem entirely, since separate
cells write to separate objects.

Rejected because **it loses the log exactly when the log matters most.** The `awslogs` driver
captures stdout/stderr at the *driver* level, so it survives the agent process being `SIGKILL`ed.
Agent-side shipping on a flush interval does not: the final chunk never lands. The two failure modes
where that happens are **Fargate Spot reclamation** and an OOM during `native-image` — and
`EcsTaskLauncher` is Spot-first by default, so this is routine rather than hypothetical. Losing the
tail of the log on exactly those two failures is not an acceptable trade for $0.002.

### Conclusion: keep the relay, resize it

CloudWatch via `awslogs` must remain the authoritative sink because it survives the agent dying.
Reading it requires credentials the client should not hold. Therefore a relay is correct, and SSE
over the Function URL is a reasonable relay.

| Mechanism | Cost per ~7 min build | Client needs AWS creds | Survives agent `SIGKILL` | Length ceiling |
|---|---|---|---|---|
| SSE relay, 1024 MB JVM arm64 (original sizing) | $0.0053 | no | yes | 900 s, handled by handover |
| **SSE relay, 384 MB JVM arm64 (current)** | **$0.0020** | no | yes | same |
| **SSE relay, 256 MB native x86_64 (current)** | **$0.0017** | no | yes | same |
| SSE relay, 256 MB native arm64 (if built on arm64) | $0.0013 | no | yes | same |
| Client tails CloudWatch via STS | ~$0 | **yes** | yes | none |
| Agent ships logs to S3 | ~$0.0010 | no | **no** | none |
| CloudWatch Live Tail | **~$0.067** | depends on holder | yes | 3 h |

### ALB, API Gateway, CloudFront — none of them removes the charge

Asked directly: can the log stream go through an ALB, API Gateway, or a public CloudFront origin instead, and
avoid Lambda charges?

**No, and the reason is the same one that forces the relay in the first place.** All three are *transports*.
None can read CloudWatch Logs. Whatever sits behind them still needs credentials and still costs compute, so
changing the transport moves the charge rather than removing it.

Rates verified against the Price List API for `eu-west-1`:

| Option | Rate | Verdict |
|---|---|---|
| **ALB** | $0.0252/h + $0.008/LCU-h | **$18.40/month always-on.** Break-even against $0.0018/build is **10,220 builds/month** — before LCU charges, and before solving the actual problem |
| **API Gateway HTTP API** | $1.00/million requests | 29-second integration timeout **kills SSE outright**. For polling it works, but a Lambda Function URL already carries requests at no extra charge, so this is pure added cost |
| **CloudFront** | $0.085/GB + per-request | Needs an HTTP origin. CloudWatch Logs is not one. Over S3 it works but every poll is a unique byte range, so there is nothing to cache — more expensive than direct S3, for no benefit |

Pointing an ALB at the Fargate task instead — so the agent serves its own logs — avoids CloudWatch but is
worse on three counts: the always-on cost above, target registration churn for tasks that live four minutes,
and log serving would compete for the CPU `native-image` is already saturating.

### The lever §6 missed: invocation lifetime, not transport

The cost is `memory × duration`, and **duration is currently pinned to the build's wall clock** because one
invocation holds the stream open for the whole build. §6 considered widening `POLL_INTERVAL` and correctly
concluded it saves nothing — the billed duration is the same whether the loop ticks every 3 s or every 10 s.
But that is the *server-side tick rate inside one long invocation*. The untouched variable is how long each
invocation lives.

**The endpoint is ready for it; the client is not.** `GET /builds/{buildId}/logs?since=<millis>` already takes
a watermark, `LogEvent.nextSince` is already a per-cell map, and the client already reconnects on
`LAMBDA_TIMEOUT`. An earlier draft of this section concluded from that it was "one constant". Reading
`ServiceBuildBackend.streamUntilTerminal` disproves it — three blockers, and the third removes the middle
ground.

**A. The reconnect cap is a count, not a duration.** The client loops
`for (int reconnects = 0; reconnects < 64; ...)` and then throws *"gave up after 64 log-stream reconnects"*.

| Budget | 64 reconnects covers |
|---|---|
| 780 s (current) | 13.9 hours — irrelevant |
| ~3 s (one tick) | **192 seconds** |

A 116-second example-app build survives that. The 700-second petclinic build **fails outright**. The cap has
to become time-based first.

**B. The `since` query parameter is scalar, so frequent reconnects duplicate output.** `nextSince` is a
`Map<String, Long>` per cell — but the parameter is `@QueryParam("since") Long`, so the client collapses the
map to its minimum:

```java
since = last.nextSince().values().stream().min(Long::compareTo).orElse(since);
```

The resource then applies that one timestamp to every cell. At one reconnect per build that replays a little.
At 120 reconnects it replays, every time, every line from every cell ahead of the slowest — and the two
architectures finish minutes apart (measured: 5m21s against 8m46s on petclinic).

The irony is precise: `nextSince` is a map **because** a scalar caused a real bug (commit `7782771`). The
query parameter kept the scalar, and the long budget is what hides it. Polling would push that scalar through
120 times a build. Fixing it is a wire-contract change.

**C. There is no moderate budget that saves anything.** Lambda bills invocation duration. An invocation that
holds the stream for 60 s is billed 60 s, so six of them for a 360 s build is still 360 s billed — **zero
saving**. Measured on the native deployment just now: median billed duration 784 ms for ordinary API calls,
**116,406 ms for the one invocation holding the stream.** The saving exists only if each invocation does one
poll and returns, which forces the extreme case and therefore forces A and B.

**Revised recommendation: not worth it as scoped.** It costs a wire-contract change plus a client-loop change
to save ~$0.0016 per build, and it would route a per-cell watermark through a scalar 120 times per build —
re-exposing the shape of a bug this project already fixed once. Revisit only if per-cell `since` is wanted for
another reason; then polling becomes nearly free on top of it.

### ECS Express Mode — always-on economics for the same work

Express Mode provisions an HTTP service fronted by an ALB. Two readings of the idea, both worse.

**As a replacement for the relay Lambda**, it still has to read CloudWatch — so it does the Lambda's job on
an always-on cost base:

| | eu-west-1 |
|---|---|
| ALB | $18.40/month |
| Fargate 0.25 vCPU / 0.5 GiB ARM, continuous | $7.21/month |
| **Total** | **$25.60/month** |
| Break-even against $0.0018/build | **~14,200 builds/month** |
| Lambda today, at 100 builds/month | **$0.18/month** |

**As the build task serving its own logs** — the more interesting reading, since the task already has a public
IP and is already paid for — it fails the durability test above, for exactly the reason agent-side S3 shipping
did. The `awslogs` driver captures stdout at the *driver* level and survives the agent being `SIGKILL`ed; an
in-process HTTP server does not. Spot reclamation and an OOM during `native-image` are the two failures where
the tail of the log is the whole value, and Spot is the default. It would also serve logs from CPU that
`native-image` is saturating, and require the client to reach an ephemeral task.

### Is there an `awslogs` forwarder — EventBridge or otherwise?

**Not to EventBridge.** Subscription filters — the real-time forwarding mechanism for an arbitrary log group —
support exactly three destinations: a **Kinesis data stream**, a **Firehose delivery stream**, or a **Lambda
function** (plus cross-account logical destinations, themselves backed by Kinesis or Firehose). EventBridge is
not among them, in either the API or the CloudFormation resource.

The newer **delivery API** (`PutDeliverySource` / `PutDeliveryDestination` / `CreateDelivery`) does target
CloudWatch Logs, S3, Firehose and X-Ray — but its sources are AWS services: *"Only some AWS services support
being configured as a delivery source."* ECS container stdout arriving through `awslogs` is customer log data
in a log group, not a vended-log source, so that API does not apply here.

#### The one forwarding variant that survives the durability test

**Subscription filter → Firehose → S3.** Unlike agent-side shipping, this *preserves* the property that made
`awslogs` worth keeping: log events still reach CloudWatch through the driver first, and the filter delivers a
**copy**. A `SIGKILL`ed agent still has its tail captured. That distinction is not drawn in the rejection
above, and it makes this the only variant that could take the relay off the live path without losing logs on
Spot reclamation.

It is still not suitable, for a reason that is not cost: **Firehose buffers before delivering.** Small
per-build volumes would sit in the buffer rather than reaching S3 promptly, so the client would tail a log
well behind the build. For a 6-minute build that defeats the purpose of streaming. (The exact minimum buffer
interval for S3 delivery was not verified here; the conclusion holds at any value in tens of seconds.)
Firehose ingestion is a few cents per GB — immaterial at these volumes. Latency is the blocker, not price.

**Verdict:** worth adding as an *archive* path if post-mortem log retention in S3 is ever wanted. Not a
replacement for the live stream.

#### The EventBridge answer that does exist

ECS publishes **task state change** events to EventBridge. That is the other half of what the SSE stream
carries: `refreshFromEcs` currently issues a `DescribeTasks` on every tick to notice
`PROVISIONING → RUNNING → STOPPED`. Those transitions could be event-driven rather than polled.

It does nothing for log content and does not shorten the held-open invocation, so it is not a cost lever on
its own. It becomes one in combination with the polling change above: if invocations are short, a status-change
event is what lets the client back off its poll interval without losing responsiveness at the moment a cell
finishes.

The `STREAM_BUDGET` handover at 780 s is worth defending rather than engineering away. It exists
because Lambda caps at 900 s, and `native-image` builds routinely exceed 13 minutes, so reconnect is
the normal path, not an edge case. But `LogStreamResource` already implements it correctly —
per-cell watermarks, `nextSince` as a map rather than a scalar, `LAMBDA_TIMEOUT` distinguished from
`BUILD_TERMINAL`. That complexity is written and tested, and the class docstring records the real
bug (commit `7782771`) that a naive single-watermark version caused. Replacing the mechanism risks
reintroducing it for no cost benefit.

**Action: change the sizing, not the mechanism.** Deploy native, measure `Max Memory Used` on a real
streaming invocation, then reduce `memorySize` toward 256 MB. `AwsClientProducer` already uses
`UrlConnectionHttpClient` on every client — no Netty, no Apache connection pool — which is what
makes a sub-512 MB native binary realistic. Watch for slow ticks as CPU share falls (256 MB ≈ 14% of
a vCPU): `LogStreamResource` uses `.onOverflow().drop()`, so an overrunning tick degrades into a
wider next window rather than an error, meaning the effective poll rate can silently fall below the
configured one.

Net effect: the log path drops from the original $0.0053 to ~$0.0005 measured (§4) with no architectural
change and no
new failure modes. That is a ~$0.004 saving — worth doing because it is two configuration values,
not because it is material money.

## 7. Versus an always-on box, on plain on-demand rates

The comparison that matters for a decision, and the one §7 did not make: §7 priced an always-on
`m7g.xlarge` against a per-build cost, using Savings Plans. Two problems with that as a decision aid — it
compares a box sized for *building* against the scale-out architecture, when the whole point is that the
client does not need to build; and Savings Plans require a one- or three-year commitment, which is not the
position most people are in when they first look at this.

**All figures below are plain on-demand, no commitment** — the saving available today, without signing
anything. Verified against the Price List API for
`eu-west-1` on 2026-09-27. Savings Plans reduce every EC2 row by roughly 24% (1-year) or 47% (3-year), and
Fargate by about 21% or 46%, so the ranking does not change — the numbers here are the ones available
immediately.

### The machines

| Instance | vCPU / RAM | On-demand | Always-on monthly (730 h) |
|---|---|---|---|
| `r6a.2xlarge` — a developer workstation of the size this project was developed on | 8 / 64 GiB | $0.5076/h | **$370.55** |
| `r6a.xlarge` | 4 / 32 GiB | $0.2538/h | $185.27 |
| `m7g.xlarge` — §7's build box | 4 / 16 GiB | $0.1819/h | $132.79 |
| **`t4g.large` — enough to run Maven and this plugin** | 2 / 8 GiB | $0.0736/h | **$53.73** |
| `t4g.medium` | 2 / 4 GiB | $0.0368/h | $26.86 |

`t4g.large` is the client recommendation, at 2 vCPU / 8 GiB: the same envelope that makes AgentCore Runtime
viable for the client (see
[`microvm-platform-fit.md`](design/control-plane/microvm-platform-fit.md)). `t4g.medium`'s 4 GiB is likely
too tight for Quarkus augmentation of a 233-jar application, which is an ordinary Maven JVM but not a small
one — untested, so not recommended.

### Cost per two-architecture build

| | |
|---|---|
| `t4g.large` client, for a ~6 minute build | $0.0074 |
| The build itself, Spot — both Fargate cells, the Lambda, IPv4, logs and storage (§4) | $0.0127 |
| **Total, client billed only while building** | **$0.0201** |
| *with Fargate on-demand instead of Spot* | *$0.0376* |

### Monthly totals

| Builds/month | Client on-demand + Fargate | Client always-on + Fargate | `r6a.2xlarge` always-on |
|---|---|---|---|
| 10 | **$0.20** | $53.86 | $370.55 |
| 50 | **$1.00** | $54.36 | $370.55 |
| 100 | **$2.01** | $55.00 | $370.55 |
| 500 | **$10.03** | $60.08 | $370.55 |
| 1,000 | **$20.06** | $66.43 | $370.55 |
| 5,000 | **$100.30** | $117.23 | $370.55 |

The always-on box costs the same whether you build once a month or five thousand times — **but only up to its
capacity ceiling, which this table ignores.** See the correction below: one compile-capable machine finishes
about 335 builds a day, so past that the right-hand column has to grow too and the crossover implied here does
not exist.

### The argument that is not about cost

A single x86_64 box **cannot produce the arm64 binary at all**. GraalVM does not cross-compile, so the
alternatives are QEMU emulation — impractical for a `native-image` compile — or a second machine. An
honest comparison of the always-on box against scale-out is therefore between *one architecture* and
*two*, and the $370.55 buys the lesser capability.

So a two-architecture always-on setup is $370.55 for the x86_64 box plus an arm64 one. Pairing it with the
`m7g.xlarge` priced above gives **$503.34/month** before anyone has run a single build — against $0.20 for
ten builds on the scale-out path.

### Where the always-on box still wins

- **Sustained very high volume** — past roughly 600 builds/day the always-on box is cheaper, and it has no
  per-build provisioning or image-pull overhead.
- **Latency on small projects.** Measured on the example app: local compile 40–55 s, against ~100 s billed
  Fargate time including ~20 s provisioning and ~8 s image pull. For a trivial project the offload is
  slower, which is why the plugin runs host-matching cells locally by default.
- **No network dependency.** A build that cannot reach the control plane cannot proceed.

### Correction: always-on capacity is not flat, and this section assumed it was

Everything above this point in §7 compares a per-build cost against a **fixed** monthly machine cost. That is
wrong, and it flattered the always-on column at high volume in one direction and the offload column in the
other.

**A `native-image` compile saturates 4 vCPU for about 4.3 minutes** (measured: 258 s arm64, 250 s x86_64, at
4024–4095 of 4096 CPU units). So a 4-vCPU machine runs **one compile at a time**:

```
86,400 s/day ÷ 258 s = ~335 builds/day per machine, at a theoretical 100% utilisation
```

Always-on capacity therefore scales with volume exactly as offloading does. 1,000 builds/day needs **three**
machine pairs; 6,000 needs **eighteen**. Machine size does not change the arithmetic — a `c7g.4xlarge` costs
about 4× a `c7g.xlarge` and completes about 4× as many compiles, so cost per build is flat across sizes.

The right comparison is therefore **per build at a given utilisation**, not monthly totals. Minimum
compile-capable pair, `c7g.xlarge` + `c7i.xlarge` (4 vCPU / 8 GiB each), $0.1550/h and $0.19152/h:

| Utilisation | Always-on, on-demand | Always-on, 3-yr SP |
|---|---|---|
| 100% (unreachable) | $0.0247 | **$0.0131** |
| 70% | $0.0354 | $0.0187 |
| 50% | $0.0495 | $0.0262 |

Against **$0.0127** offloaded on Spot, **$0.0302** offloaded on-demand.

| Scenario | Winner |
|---|---|
| Interruptible both sides, any utilisation | **Offload** — except a 3-yr commitment at ~100%, which is a tie |
| Guaranteed both sides, ~100% utilisation | Always-on, $0.0247 against $0.0302 |
| Guaranteed both sides, 70% utilisation | **Offload**, $0.0302 against $0.0354 |

The mechanism is unglamorous: **Fargate Spot is roughly 70% off, and always-on EC2 carries no discount unless
you commit for one or three years.** Parity requires both the commitment and near-perfect utilisation. Bursty
agent and CI traffic is precisely what does not deliver the second, and the price of chasing it is a queue —
latency rather than dollars.

**What survives from the tables above:** the comparison against a *developer workstation* (`r6a.2xlarge`,
64 GiB) is still valid as a statement about that machine, but it is the wrong machine to compare against. 8 GiB
is enough to compile, so the honest always-on baseline is $252.96/month for a pair, not $503.33 for two
oversized boxes.

## 7b. Versus EC2, with Compute Savings Plans

> §7 above makes the same comparison on plain on-demand rates, against a correctly-sized client. This
> section is retained for its Savings Plan figures, which apply a further ~24% (1-year) or ~47% (3-year) to
> every EC2 row.

### Ephemeral instance per build

Launch, build, terminate. 400 s of work plus EC2 boot and a Docker pull ≈ 550 s billed:

| Line item | Cost |
|---|---|
| `m7g.xlarge`, Compute SP 1 yr | $0.0211 |
| gp3 root, 30 GiB | $0.0006 |
| Public IPv4 | $0.0008 |
| Log streaming / orchestration (still needed) | $0.0005 |
| **Total** | **≈ $0.023** |

**Loses to Fargate Spot at $0.0127** (real-project build), and you inherit AMI patching plus a launch/terminate
orchestrator the current design does not need.

### Always-on build box

| Pricing | `m7g.xlarge` monthly (730 h) | Break-even vs. $0.0127/build |
|---|---|---|
| On-demand | $132.79 | ~10,450 builds/month |
| Compute SP 1 yr | $100.81 | **~7,940 builds/month (~265/day)** |
| Compute SP 3 yr | $69.79 | ~5,500 builds/month (~185/day) |

Below those volumes the dedicated box loses on cost, and it additionally serialises the concurrent matrix
cells that Fargate runs in parallel — which is the point of the offload, not a side benefit. The
break-evens roughly doubled once the billed window was measured rather than estimated, so the dedicated
box is a worse trade than the original analysis suggested.

### Two subtleties that matter more than the rate table

**A Savings Plan is a 24×7 $/hour commitment, not a discount coupon.** A handful of 5-minute builds
per day cannot absorb one. A CSP only helps this workload if there is *already* baseline
steady-state spend that the build minutes ride inside of — in which case the marginal cash cost of
CSP-covered Fargate is effectively $0 up to the commitment, while Fargate Spot is always
incremental spend. For an organisation in that position, `forceRemote` onto **on-demand Fargate
under an existing CSP** can beat Fargate Spot on cash out the door, even though Spot's list rate is
~45% below a 3-year CSP Fargate rate.

**Compute Savings Plans do not cover Fargate Spot.** The current `FARGATE_SPOT`-first
`capacityProviderStrategy` in `EcsTaskLauncher` deliberately opts out of any Savings Plan discount
the account has bought. That is the right default for an account with no commitment and the wrong
one for an account with an underconsumed commitment — which argues for making the capacity
provider strategy configurable rather than hardcoding Spot-first.

## 8. Isolated agents: the case where the saving multiplies

The use cases above assume one client. The one that changes the economics is **many isolated agents**, each
in its own MicroVM, each occasionally needing a native build — an AI agent working on a codebase, a
multi-tenant CI executor, a per-session sandbox.

Isolation is the point of that topology: hardware-enforced separation per tenant or session, which is
exactly what you cannot get by sharing one build box. The problem is that isolation removes the ability to
amortise, so **every** agent must be sized for its peak.

### Sizing every agent for a compile it rarely runs

Measured, a `native-image` build of a 233-jar application needs 5.3–5.6 GB peak RSS and saturates 4 vCPU.
An agent that compiles in-session must therefore be provisioned for that — on AgentCore Runtime that means
the 2 vCPU / 8 GB session ceiling, and at 2 vCPU the compile takes roughly twice as long.

An agent that offloads needs only enough to run Maven and this plugin: resolve a classpath, run framework
augmentation, upload inputs, hold an SSE connection, download artifacts. That is a small envelope, and the
compile burst goes to shared Fargate capacity billed per build.

### Consequence 1: concurrency within a fixed quota

Lambda MicroVM account memory is a **pooled quota across `RUNNING` and `SUSPENDED` instances**
([quotas](https://docs.aws.amazon.com/lambda/latest/dg/gettingstarted-limits.html)) — so a suspended agent
still consumes it.

| Region group | Quota | Agents at 8 GB (compiles locally) | Agents at 2 GB (offloads) |
|---|---|---|---|
| Most regions | 400 GB | **50** concurrent | **200** concurrent |
| `us-east-1`, `us-west-2`, `us-east-2`, `ap-northeast-1` | 1,024 GB | 128 concurrent | 512 concurrent |

**A 4× increase in concurrent isolated agents within the same account quota**, burstable to 4× that again.
This is not a cost saving — it is a capacity ceiling moving, and no amount of budget raises it without a
quota increase request.

### Consequence 2: the session pays for its envelope throughout

An agent session runs up to 8 hours and compiles for minutes of it. Sizing the session for the compile
means paying build-sized memory for the whole session:

| | Session envelope | Memory billed for a 60-minute session |
|---|---|---|
| Compiles in-session | 8 GB | 8.0 GB-hours |
| Offloads | 2 GB | **2.0 GB-hours** |

For a session doing three 6-minute builds — 30% of the hour compiling — that is **4× less memory billed**,
plus $0.0127 per build on Fargate Spot. The ratio improves as the session gets longer or builds get rarer,
which is the normal shape for an agent that spends most of its time waiting on a model or a human.

AgentCore Runtime bills CPU and memory consumption per second and reclaims idle memory after 120 seconds
([pricing](https://aws.amazon.com/bedrock/agentcore/pricing/)), which softens this — but reclamation cannot
help with a session sized large enough to compile, because the envelope is still provisioned and the CPU
allocation still follows the memory. **The dollar figure depends on AgentCore consumption rates not verified
here**; the GB-hour ratio and the concurrency multiplier are from documented quotas and measured usage.

### A concrete instance: hosted agent sandboxes

[Kiro cloud sessions](https://kiro.dev/docs/cloud-sessions/) are this shape — an isolated sandbox is
provisioned, repositories are cloned server-side, the agent runs builds and shell commands inside it, and
it is torn down.

**Observed specs, not published.** Kiro documents the sandbox lifecycle, network access, and environment
configuration, but not its hardware. Inspection of a live session shows a VM with **Podman and root access,
on `x86_64`, with no way to select a different architecture**, in `us-east-1` only. Treat this as an
observation that could change, not a contract — but it is the situation as of 2026-09-27.

**That makes `arm64` impossible rather than merely awkward.** This is the strongest form of the case for
offloading, and it is not a cost argument at all. GraalVM does not cross-compile. Root and Podman do not
help: the only local route to a foreign architecture is QEMU, and emulating a compile that saturates 4 vCPU
for 3–5 minutes is not a workaround. Measured natively that compile is 3m06s–5m01s
([`fargate-task-resource-usage.md`](design/control-plane/fargate-task-resource-usage.md)); the emulated
figure was not measured here because the approach was never a candidate.

So for anyone who wants to work in an agent sandbox *and* ship `arm64` native binaries, the sandbox alone
cannot do it. **The rest of this section is about cost; this part is about whether the workflow exists.**

Verified against this plugin's code: with `scaleout-build.forceRemote=true`, `splitLocalAndRemote` returns
an empty local list, `NativeImageBuildExecutor` is never constructed, and no `native-image` command is
built. There is no upfront `GRAALVM_HOME` validation either. **The client needs Maven, a JDK, this plugin,
and two IAM permissions — no GraalVM or Mandrel toolchain.** For Quarkus, `-Dquarkus.native.sources-only=true`
produces `target/native-sources` without invoking `native-image`, so that path needs no toolchain either.

What that buys in an ephemeral sandbox:

- **No toolchain to provision per session.** A fresh sandbox would otherwise need a multi-hundred-megabyte
  GraalVM install inside its setup-command budget, every session.
- **The build environment is the agent container image**, pinned and multi-arch in ECR, rather than
  something the sandbox must reproduce. That directly addresses the usual objection to cloud sandboxes —
  that a customised local build environment is not present in a fresh one.
- **No dependence on undocumented sandbox specs.** A `native-image` compile wants 5.3–5.6 GB and saturates
  4 vCPU. Against an unpublished envelope that is a gamble; the offloading client's footprint is small and
  known.
- **Both architectures, concurrently.** A sandbox is one architecture and cannot cross-compile, so locally
  the choice is one binary or none. Remotely both cells run in parallel: ~6 minutes for both.

### What it does not buy, stated plainly

- **It is not a cost saving on the hosted side.** Kiro's documentation is explicit that cloud sessions carry
  *no separate charge for cloud compute*. Offloading therefore **adds** about $0.0127 per build to *your*
  AWS bill; it does not remove a charge from somewhere else.
- **It does not relieve a session concurrency cap.** Kiro caps concurrent sessions (10) rather than pooled
  memory, so the 4× multiplier above **does not transfer**. That multiplier applies to a self-hosted fleet
  on Lambda MicroVMs or AgentCore Runtime, where the account quota is pooled memory and agent size
  therefore determines how many fit.
- **It does not shorten the wall clock.** The agent streams logs and waits; a six-minute compile is still
  six minutes of session.

### Two operational prerequisites

- The sandbox needs **AWS credentials carrying the two IAM permissions** and egress to the Function URL.
  Kiro sandboxes support environment variables and setup commands, which is where that belongs.
- **Co-locate the control plane with the sandbox.** Kiro cloud sessions run in `us-east-1` only; this
  project's control plane is deployed in `eu-west-1`. Split across regions, every artifact download crosses
  a region boundary and is billed as inter-region transfer on top of the build — roughly 256 MB per
  two-architecture build, on measured artifact sizes. Deploying the control plane in the sandbox's region
  avoids it. The rate is not quoted here because it was not verified.

### Why the architecture fits rather than merely costs less

Isolation is needed where tenant state lives — the agent, holding a workspace and credentials. It is not
needed for the compile, which is stateless: inputs in, binary out, content-addressed, no tenant context
beyond the per-owner prefix the control plane already enforces
([`storage-layout-and-isolation.md`](design/control-plane/storage-layout-and-isolation.md)). A Fargate task
per cell is itself a fresh container, so the offload does not weaken isolation; it moves the expensive part
to where sharing is safe.

Which is the argument in one line: **isolate what holds state, pool what does not.**

## 9. Where the offload actually pays

Not on cost per build for a host-matching single architecture. On the two things that cannot be
bought locally:

- **Architectures you have no hardware for.** GraalVM `native-image` cannot cross-compile, so this
  is not "cheaper than the alternative" — it is the only alternative to owning both kinds of machine
  or shipping a JVM-only fallback.
- **Matrix parallelism.** N cells complete in roughly one cell's wall time, at N × ~1.6¢.

At ~1.6¢ per cell, compute cost is negligible against a developer's 5–7 minutes of wall clock. The
architecture-coverage question, not the cost question, is where the value sits.

## 10. Assumptions, restated

Figures above that are **retrieved**: all Fargate on-demand rates (ARM and x86_64, vCPU and memory), all
Lambda rates (ARM and x86_64), all EC2 on-demand rates, all Compute Savings Plan rates, and every
configuration value in §2. Rates were re-verified against the Price List API on 2026-09-25.

Figures that are **measured** (§4, and the two documents it links): billed task window, image pull
duration, provisioning delay, `native-image` compile time, Fargate CPU/memory/storage utilisation, and
Lambda memory, duration and cold start in both modes.

Figures that are **assumed and should be measured**:

- Fargate Spot discount (−70% used; AWS publishes "up to 70%"). Not available from any pricing API.
- **That the Spot path is the one actually taken.** `spot-preferred` is the default, on the basis that a
  3–5 minute build rarely overlaps a reclamation. Observed: 20 tasks during development, zero
  interruptions.

  **Being wrong costs the whole build, not a relaunch.** The control plane does not retry an interrupted
  task — `refreshFromEcs` marks the cell `FAILED` with the stopped reason. So the on-demand row above is
  not a worst case that happens automatically; it is what you pay by choosing
  `-c fargateCapacityStrategy=on-demand-preferred` up front. Deployments where a lost build is expensive
  should do that rather than rely on a retry that does not exist. See
  [`design/control-plane/HANDOVER.md`](design/control-plane/HANDOVER.md) for the gap.
- ~~400 s billed task window for a 300 s compile — driven by agent image pull time, which nothing in
  the repository records.~~ **Now measured** (§4): 96–122 s per task, with an 8–9 s pull. The estimate was
  high by roughly 3.5×, mostly because it assumed a 60–90 s pull. See
  [`design/control-plane/fargate-task-resource-usage.md`](design/control-plane/fargate-task-resource-usage.md).
- Reaper per-invocation duration (~300 ms assumed for a warm 512 MB JVM doing one GSI query). Not
  measured; it is the one Lambda in the stack that `scripts/lambda-usage.sh` does not cover.
- Whether these figures hold for a realistic project. Everything here is the example app, whose classpath
  is two jars. A build with hundreds of dependencies will upload more inputs, hold more memory during
  `native-image`, and compile for longer. This was the reason not to trim task memory on a 7% reading, and
  re-measuring against a real project confirmed it: 5.2 GB rather than 1.2 GB, so the task settled at
  8 GiB rather than the 8 GiB minimum-for-4-vCPU being a lucky guess.
- CloudWatch Logs / DynamoDB / S3 lumped at ~$0.003. Individually sub-cent; the S3 component is
  near-zero on repeat builds because staging is content-addressed (see
  `docs/design/control-plane/storage-layout-and-isolation.md`).
