# Design: Spot interruption retry

**Status: design only. No code written.**

## Problem statement

`SPOT_PREFERRED` is the correct default for most builds — the exposure window is 3–5
minutes, the discount is ~70%, and observed interruption rate across this project's
development is zero. But when a Spot interruption does land, the build fails
permanently: the developer must re-run `mvn package` manually. There is no retry.

This is documented in the `SPOT_PREFERRED` javadoc and in the `FargateCapacityStrategy`
class, but it is worth restating plainly: `EcsTaskSupervisor` implements relaunch-and-
escalate logic, but the control plane uses only its `logStreamNameFor` helper. The
supervision loop is unreachable in the deployed architecture.

The fix is to wire the supervision loop back in, scoped to the one case worth automating:
**Spot interruption → single retry → on-demand**.

---

## Design decision: server-side, opt-in via CDK

**Server-side**, not client-side. The client sees "Essential container in task exited"
either way — it cannot distinguish a Spot interruption from an OOM kill or a genuine
build failure. Only the control plane can read ECS `stoppedReason`.

**Opt-in via CDK**, not always-on. The operator who deploys the stack decides the
policy. A user who chose `SPOT_ONLY` as a hard cost ceiling should not have the
control plane silently spend on-demand capacity on their behalf. The default is `true`
because most deployments want reliability over micro-optimized cost, but the knob must
exist.

**One retry only**, not indefinite. If on-demand also fails, it is a genuine build
failure. Spot interruptions do not cause two consecutive on-demand failures.

---

## Configuration

New field on `ControlPlaneConfig.Ecs` (CDK `EcsConfig`):

```java
/** Retry once with on-demand capacity when a Spot task is interrupted. Default: true. */
boolean spotInterruptionRetry = true;
```

CDK stack property (install-time knob, not runtime):

```typescript
spotInterruptionRetry?: boolean  // default: true
```

`SPOT_ONLY` ignores this flag. Its semantics are "make cost a hard constraint and fail
rather than quietly spending more." Silently upgrading to on-demand would violate that.
The `SPOT_ONLY` javadoc will note this explicitly.

`ON_DEMAND_ONLY` and `ON_DEMAND_PREFERRED` are unaffected — they cannot be interrupted.

---

## What changes

### `ControlPlaneConfig.Ecs`

```java
private boolean spotInterruptionRetry = true;
```

Exposed as a CDK context key `spotInterruptionRetry`, parsed alongside
`capacityStrategy`. Stored in SSM with the other deployment-time config, read at Lambda
cold start.

### `BuildService` / cell launch path

Currently, when a cell's ECS task enters `STOPPED`, the service reads `stoppedReason`
via `DescribeTasks`, marks the cell `FAILED`, and stores the reason. The new path:

```
task STOPPED
  └─ stoppedReason == "Your Spot Task was interrupted."
       AND config.spotInterruptionRetry == true
       AND this is the first attempt (spotInterruptions == 0 on the cell record)
       AND FargateCapacityStrategy != SPOT_ONLY
       └─ relaunch with toStrategy(forceOnDemand=true)
            update cell record: spotInterruptions++, taskArn = new ARN
            log: "[CELL] Spot interrupted, retrying with on-demand"
  └─ otherwise: mark FAILED as today
```

`spotInterruptions` is already a field on `BuildRecord.CellRecord`. It is incremented
here (currently always 0 because the retry path is unreachable).

**One retry only** is enforced by the `spotInterruptions == 0` guard. If the on-demand
retry also fails for any reason, the cell fails normally.

### Log output (Maven console)

The retry must be visible. A silent retry that costs 3× more without the developer
seeing it is worse than a failed build. The log line must appear in the SSE stream:

```
[NATIVE/ARM64] Spot interrupted — retrying with on-demand capacity
```

This is a cell-level log event, same as any other build log line, so it appears inline
in the Maven output between the build's normal lines.

### `EcsTaskSupervisor`

The supervisor already implements everything needed: `SPOT_INTERRUPTION_STOPPED_REASON`
detection, `forceOnDemand` relaunch via `FargateCapacityStrategy.toStrategy(true)`, and
log-stream reset on relaunch. It was built for the direct-ECS path and stranded when
that path was deleted.

The service does not call `supervise()` — that method runs a synchronous poll loop
inside a thread, which does not fit the Lambda execution model. Instead, the service
uses the detection logic from the supervisor and the relaunch logic from
`EcsTaskLauncher` directly. The relevant pieces are:

- `EcsTaskSupervisor.SPOT_INTERRUPTION_STOPPED_REASON` — the exact string to match.
  Already `static final`, already the right scope.
- `FargateCapacityStrategy.toStrategy(true)` — the on-demand escalation. Already
  implemented.

No new classes needed. The wiring happens in the cell-monitoring path of `BuildService`.

### Reaper interaction

The reaper already handles cleaning up stale cells. A cell in `RUNNING` state with a new
`taskArn` (post-retry) is handled correctly by the existing reaper logic — it reads the
current `taskArn` from the cell record and acts on that.

---

## Hazards

**The `stoppedReason` string is not a typed API value.**
`"Your Spot Task was interrupted."` is confirmed against AWS troubleshooting docs and
support threads, not a typed enum value. A wording change on AWS's side silently breaks
detection — a Spot interruption would then be treated as an ordinary failure rather than
retried. This is already noted in `EcsTaskSupervisor`'s javadoc. Mitigation: the match
is case-sensitive and exact, so false positives are not a concern; the only risk is false
negatives (missed retries), which degrade gracefully to today's behaviour.

**`stoppedReason` is only available for one hour after the task stops.**
The service reads it in the same poll cycle that discovers the task stopped, so this
window is not a concern for the retry path. It would matter if a reaper tried to
classify a missed task post-hoc, which it does not.

**Cost transparency.**
A retry with on-demand capacity costs roughly 3× the Spot price for the retried portion.
The developer sees the log line. There is no other charging signal in the Maven output.
If the deployment operator wants tighter control, `ON_DEMAND_ONLY` or
`spotInterruptionRetry=false` are the right tools.

---

## What is not in this design

**Retry for `SPOT_ONLY`.** Its semantics are a deliberate hard cost ceiling. No retry.

**More than one retry.** Two consecutive on-demand failures is a genuine infrastructure
problem, not bad luck. The developer should know.

**Client-side retry.** The client cannot read `stoppedReason` — it is not in any API
response the plugin receives. Server-side is the only place this can be done correctly.

**Retry for EC2/Managed Instances Spot.** The exact `stoppedReason` string for non-Fargate
Spot interruptions has not been verified. Extending retry to those launch types requires
a confirmed string, not an assumption. Guarded in `EcsTaskSupervisor.isSpotInterruption`.

---

## Testing

- **Unit**: `spotInterruptions == 0` guard, `SPOT_ONLY` veto, log line emission,
  `forceOnDemand` flag wired to `toStrategy(true)`.
- **Unit**: second failure (on-demand retry fails) marks cell `FAILED`, does not retry
  again.
- **Unit**: `SPOT_PREFERRED` + `spotInterruptionRetry=false` — cell fails on first
  interruption, no relaunch.
- **Unit**: `stoppedReason` mismatch (OOM, user-initiated) — no retry.
- **`@QuarkusTest`**: full cell lifecycle with mocked ECS; inject a stopped task with
  Spot reason on first launch, succeeded task on second; assert cell ends `SUCCEEDED`
  and `spotInterruptions == 1`.
