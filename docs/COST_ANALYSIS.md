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

**The non-obvious finding: the SSE Lambda costs about as much as the Fargate Spot compute it is
watching** — roughly a third of the per-build bill goes to a Lambda sitting in a 3-second polling
loop. See §5 for the sizing levers and §6 for why the relay is nonetheless the right mechanism.

## 2. What the code pins down

Every figure below follows from configuration that is in the repository, not from assumptions about
how the system might be deployed:

| Parameter | Value | Source |
|---|---|---|
| Task size | 4 vCPU / 16 GiB | `BuildMojo` `requestedCpu=4096`, `requestedMemory=16384` |
| Capacity provider | `FARGATE_SPOT` preferred, `FARGATE` fallback | `EcsTaskLauncher` |
| Ephemeral storage | 20 GiB (included, unbilled) | `requestedEphemeralStorageGiB=0` |
| Networking | public subnets, `natGateways(0)`, `assignPublicIp` | `ControlPlaneInfraStack` — one public IPv4 per task, no NAT gateway |
| SSE Lambda | **1024 MB**, ARM64, held open for the whole build | `memorySize(jvmMode ? 1024 : 512)`; deployed mode is `jvm/arm64` |
| SSE poll cadence | 3 s (`FilterLogEvents` + `refreshFromEcs` per tick) | `LogStreamResource.POLL_INTERVAL` |
| SSE handover | 780 s, then client reconnects | `LogStreamResource.STREAM_BUDGET` |
| Client heartbeat | every 30 s | `ControlPlaneConfig.heartbeatIntervalSeconds` |
| Reaper | 512 MB ARM64, `rate(1 minute)`, always on | `ReaperSchedule` |
| DynamoDB | `PAY_PER_REQUEST` | `BuildsTable` |

Note the SSE Lambda is 1024 MB specifically *because* the stack is currently deployed in JVM mode.
A native deploy halves it to 512 MB.

## 3. Rates

All rates `eu-west-1`, retrieved from the AWS Price List API and from the SavingsPlans
`DescribeSavingsPlansOfferingRates` API (Compute Savings Plan, No Upfront), September 2026.
Re-verify before reusing — AWS republishes the price list continuously.

### Fargate, 4 vCPU / 16 GiB ARM64

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

## 4. Per-build cost: one 5-minute `native-image` build

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
| **SSE Lambda, 1 GB × 400 s** | **$0.0053** |
| Heartbeats + create/status invocations | ~$0.0005 |
| CloudWatch Logs ingest, DynamoDB, S3 staging | ~$0.0030 |
| **Total, Spot path** | **≈ $0.016** |
| *(Fargate on-demand instead, i.e. Spot reclaimed or unavailable)* | *$0.0207* |
| **Total, on-demand fallback path** | **≈ $0.030** |

Plus a fixed **~$0.10–0.15/month** for the reaper firing every 60 s regardless of build activity
(43,800 invocations/month at 512 MB, plus one DynamoDB GSI query each). At 10 builds/month that
amortises to ~$0.01/build — the same order as the build itself. At 100+ builds/month it is noise.

### Sensitivity to the billed window

| Billed window | Fargate Spot | Fargate on-demand | SSE Lambda (1 GB) |
|---|---|---|---|
| 300 s (compile only, no pull) | $0.0047 | $0.0155 | $0.0040 |
| **400 s (estimate used above)** | **$0.0062** | **$0.0207** | **$0.0053** |
| 480 s (slow pull) | $0.0075 | $0.0249 | $0.0064 |

Both compute and the streaming Lambda scale linearly with the window, so shortening the image pull
improves both simultaneously. That makes agent image size a cost lever, not just a latency one.

## 5. The SSE Lambda is the most improvable line

$0.0053 of a $0.016 build — about **34%** — is a Lambda holding a connection open and running a
3-second `FilterLogEvents` + `refreshFromEcs` loop. Two changes, neither of which touches the wire
contract in `scaleout-build-control-plane-api`:

1. **Deploy the control plane in native mode.** `ControlPlaneInfraStack` already sizes the function
   at 512 MB when `runtimeMode=native`; the stack is currently deployed `jvm/arm64`, so it is
   1024 MB. This halves the streaming cost to $0.0027 outright.
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
| SSE relay, 1024 MB JVM (today) | $0.0053 | no | yes | 900 s, handled by handover |
| SSE relay, 512 MB native | $0.0027 | no | yes | same |
| SSE relay, 256 MB native | $0.0013 | no | yes | same |
| Client tails CloudWatch via STS | ~$0 | **yes** | yes | none |
| Agent ships logs to S3 | ~$0.0010 | no | **no** | none |
| CloudWatch Live Tail | **~$0.067** | depends on holder | yes | 3 h |

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

Net effect: the log path drops from $0.0053 to ~$0.001 per build with no architectural change and no
new failure modes. That is a ~$0.004 saving — worth doing because it is two configuration values,
not because it is material money.

## 7. Versus EC2, with Compute Savings Plans

### Ephemeral instance per build

Launch, build, terminate. 400 s of work plus EC2 boot and a Docker pull ≈ 550 s billed:

| Line item | Cost |
|---|---|
| `m7g.xlarge`, Compute SP 1 yr | $0.0211 |
| gp3 root, 30 GiB | $0.0006 |
| Public IPv4 | $0.0008 |
| Log streaming / orchestration (still needed) | $0.0053 |
| **Total** | **≈ $0.028** |

**Loses to Fargate Spot at $0.016**, and you inherit AMI patching plus a launch/terminate
orchestrator the current design does not need.

### Always-on build box

| Pricing | `m7g.xlarge` monthly (730 h) | Break-even vs. $0.016/build |
|---|---|---|
| On-demand | $132.79 | ~8,500 builds/month |
| Compute SP 1 yr | $100.81 | **~6,460 builds/month (~215/day)** |
| Compute SP 3 yr | $69.79 | ~4,470 builds/month (~149/day) |

Below those volumes the dedicated box loses on cost, and it additionally serialises concurrent
matrix cells that Fargate runs in parallel.

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

## 8. Where the offload actually pays

Not on cost per build for a host-matching single architecture. On the two things that cannot be
bought locally:

- **Architectures you have no hardware for.** GraalVM `native-image` cannot cross-compile, so this
  is not "cheaper than the alternative" — it is the only alternative to owning both kinds of machine
  or shipping a JVM-only fallback.
- **Matrix parallelism.** N cells complete in roughly one cell's wall time, at N × ~1.6¢.

At ~1.6¢ per cell, compute cost is negligible against a developer's 5–7 minutes of wall clock. The
architecture-coverage question, not the cost question, is where the value sits.

## 9. Assumptions, restated

Figures above that are **retrieved**: all Fargate on-demand rates, all Lambda rates, all EC2
on-demand rates, all Compute Savings Plan rates, and every configuration value in §2.

Figures that are **assumed and should be measured**:

- Fargate Spot discount (−70% used; AWS publishes "up to 70%"). Not available from any pricing API.
- 400 s billed task window for a 300 s compile — driven by agent image pull time, which nothing in
  the repository records. Worth instrumenting: it sets both compute and streaming cost.
- Reaper per-invocation duration (~300 ms assumed for a warm 512 MB JVM doing one GSI query).
- CloudWatch Logs / DynamoDB / S3 lumped at ~$0.003. Individually sub-cent; the S3 component is
  near-zero on repeat builds because staging is content-addressed (see
  `docs/design/control-plane/storage-layout-and-isolation.md`).
