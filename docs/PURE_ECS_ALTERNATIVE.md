# Pure ECS Alternative to the Step Functions Build Matrix

This branch (`feature/pure-ecs-build-matrix`) is a direct alternative to
`feature/step-functions-build-matrix` / `docs/DESIGN.md`'s Step Functions design. It answers one
question concretely: for the specific, common case of "submit a small, fixed set of Fargate builds
(e.g. x86_64 + arm64) and stream progress," is a declarative orchestrator (Step Functions) actually
necessary, or does calling ECS directly suffice?

**Short answer: pure ECS suffices, and for a small fixed matrix it's simpler.** This document is
the tradeoff analysis; §5 says which one to actually use and when to switch.

## 0. Naming: `aws-ecs`, not `fargate`

The goal is `aws-ecs:build` (parameters `aws-ecs.*`), not `fargate:build`. This is a deliberate
choice, not cosmetic: Fargate is one *launch type* within Amazon ECS, and the roadmap includes
broadening beyond it — most immediately **ECS Managed Instances**, which already supports EC2 Spot
capacity (`capacityOptionType: SPOT` on the managed-instance capacity provider, GA) and, unlike the
raw EC2 launch type, stays compatible with S3 Files for staging (see the caveat below). Naming
everything after "Fargate" would have described less and less of what the plugin does as more
launch types are added under the same ECS cluster/task-definition model. A bare `ecs` prefix was
considered and rejected as too short/generic for a goal prefix meant to be unambiguous in a
`pom.xml` or CLI transcript read out of context.

**A real constraint for any future EC2 launch type work, confirmed against AWS's docs, not
assumed:** S3 Files volumes — the mechanism this whole staging design (`S3StagingSink`/
`S3ArtifactRetriever`, the agent's S3 Files mount) depends on — are GA on **Fargate** and
**ECS Managed Instances**, but explicitly **not supported on the raw EC2 launch type**: "If you
configure an S3 file system in a task definition and attempt to run it on the Amazon EC2 launch
type, the task will fail at launch." So ECS Managed Instances is a straightforward next launch type
to add (same staging mechanism, just a different capacity provider); the raw EC2 launch type is not
— it would need a different staging mechanism (e.g. EBS-backed local disk, or reintroducing direct
S3 SDK calls inside the agent for that one launch type) before it could work at all.

## 1. Progress streaming was never a Step Functions feature

Both designs stream build progress the same way: the task's `awslogs` log driver ships stdout/
stderr to CloudWatch Logs, and `CloudWatchLogTailer` polls `FilterLogEvents` incrementally against
a known `logStreamNamePrefix`. This is unchanged, byte-for-byte, between the two branches — whoever
calls `RunTask` (the plugin directly, or a Step Functions `RunTask.sync` state) is irrelevant to how
logs get streamed back. Dropping Step Functions costs nothing here.

## 2. What pure ECS puts back on the plugin

Three things Step Functions's ASL expressed declaratively become code again:

1. **Launch.** `FargateTaskLauncher.runTask` — a direct `RunTask` call with a `capacityProviderStrategy`
   preferring `FARGATE_SPOT`, task overrides carrying the cell's environment variables. This is the
   same class (revived, largely unchanged) that existed before the Step Functions redesign.
2. **Fan-out across cells.** With no Map state doing this for us, `BuildMojo` launches and
   supervises every remote cell on its own thread from a fixed-size `ExecutorService` sized to the
   number of remote cells — bounded concurrency without needing a `MaxConcurrency` setting, since
   this plugin never launches more tasks in one invocation than that.
3. **Relaunch on Spot interruption.** `FargateTaskSupervisor` polls `DescribeTasks` and relaunches
   when a stop looks like a genuine Spot reclaim.

By default, a cell whose target architecture matches the machine running `mvn` builds locally with
no AWS calls at all (`BuildMojo.splitLocalAndRemote`) — only the non-matching architecture's cell
goes remote. Set `aws-ecs.forceRemote=true` to send every non-JVM cell to ECS regardless of host
match, e.g. to keep the local toolchain out of the loop entirely or to exercise the remote path for
an architecture that happens to match the host.

## 3. Spot interruption detection is a real, verified gap

This is the part worth being precise about rather than assuming. ECS's `stopCode` field is a
**strict enum** (confirmed against the SDK model) of exactly three values:
`TaskFailedToStart`, `EssentialContainerExited`, `UserInitiated`. **There is no dedicated stop code
for a Spot reclaim.**

AWS's documented signal instead is the free-text `stoppedReason` field. For a genuine Fargate Spot
interruption, its value is specifically the string `"Your Spot Task was interrupted."` — confirmed
against AWS's own troubleshooting guidance and support threads, not guessed. `FargateTaskSupervisor`
matches this string case-sensitively and verbatim, deliberately not with a loose substring check,
so an unrelated `stoppedReason` that happens to share words is never mistaken for an interruption
(see `FargateTaskSupervisorTest#doesNotTreatAnUnrelatedStoppedReasonAsASpotInterruption`).

This is strictly less robust than Step Functions' `Retry`/`ErrorEquals`, which matches on typed,
stable error names Step Functions itself defines. A wording change on AWS's side to this specific
message would silently break pure-ECS interruption detection with no compile-time or deploy-time
warning — it would just stop relaunching on Spot reclaims and start reporting them as build
failures instead. Also worth knowing: a stopped task's details, including `stoppedReason`, are only
queryable via `DescribeTasks` for **one hour** after the task stops — not a concern for
`FargateTaskSupervisor`'s own tight poll loop (which reads the detail immediately), but relevant if
this detection logic is ever reused against an already-stopped task discovered later.

## 4. What each approach costs and buys

| | Step Functions | Pure ECS |
|---|---|---|
| Infrastructure to deploy | State machine + its execution role | None beyond what ECS already needs |
| IAM surface | `states:StartExecution`/`DescribeExecution` on the plugin; `ecs:RunTask`/`StopTask`/`iam:PassRole` on the state machine's *own* role | `ecs:RunTask`/`StopTask`/`DescribeTasks` directly on the plugin's principal |
| Fan-out over N cells | Declarative (`Map` state, `MaxConcurrency`) | Explicit thread pool in `BuildMojo` |
| Spot interruption detection | Typed `Retry`/`ErrorEquals` | Free-text `stoppedReason` string match (§3) |
| Execution history | 90 days, queryable via `DescribeExecution`/`GetExecutionHistory` | Whatever CloudWatch Logs retention is configured; ECS's own stopped-task detail expires after 1 hour |
| Scaling to a larger matrix (JVM/PGO variants) | Free — same `Map` state, larger `cells` array | Every additional cell is still just another thread in the pool; no *new* code needed, but no built-in aggregation/tolerance semantics either |
| Code to maintain | `BuildMatrixStateMachineDefinition` (ASL), `StateMachineManager`, `StepFunctionsExecutionSupervisor` | `FargateTaskLauncher`, `FargateTaskSupervisor` |

## 5. When to use which

**Use pure ECS (this branch)** when the matrix is small and fixed — the concrete case that
motivated this branch, "x86_64 + arm64, plain native builds" — and you'd rather not deploy or
version a state machine for it. This is the simpler, more direct choice for that case.

**Use Step Functions (`feature/step-functions-build-matrix`)** if the full matrix from
`docs/DESIGN.md` §3 comes back into scope — `JVM`/`NATIVE`/`NATIVE_PGO_INSTRUMENT`/
`NATIVE_PGO_OPTIMIZE` × architecture, up to 7 cells — or if the typed `Retry`/`Catch` semantics and
90-day execution history matter more than the extra infrastructure. Nothing about the matrix
computation, staging (`S3StagingSink`/`S3ArtifactRetriever`), task definition registration
(`TaskDefinitionRegistrar`), or the agent (`AgentMain`) differs between the two branches — only the
orchestration layer above `RunTask` changes.

## 6. Not yet validated against live AWS

Same caveat as both prior designs: no ECS cluster, VPC, or S3 Files filesystem has been
provisioned. `FargateTaskSupervisor`'s Spot-interruption detection in particular has only been
exercised against a mocked `stoppedReason` string matching AWS's documentation — it has not been
observed against a real Spot reclaim.
