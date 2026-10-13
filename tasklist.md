# Lambda MicroVM backend — task list

Written 2026-10-07 so a fresh session can resume without re-deriving context. Delete this file
once Phase 1 is fully implemented and deployed; it is a working checklist, not a design doc.

## Where the actual design lives

Read these in full before writing any code — they are the source of truth, this file is just the
checklist derived from them:

1. [`docs/design/control-plane/microvm-build-backend-phase1.md`](docs/design/control-plane/microvm-build-backend-phase1.md)
   — **Phase 1, currently being implemented.** No suspend/resume, no inbound connector, one build
   per MicroVM, additive to Fargate for `arm64` cells only. This is the active work.
2. [`docs/design/control-plane/microvm-warm-pool-phase3.md`](docs/design/control-plane/microvm-warm-pool-phase3.md)
   — **Phase 3, design only, not started.** Per-owner content-addressed MicroVM reuse (suspend
   instead of terminate, resume if the same owner rebuilds the same classpath) plus a persistent
   multi-task agent with SSE log streaming. Deliberately reverses two of Phase 1's decisions (adds
   an inbound connector, mints auth tokens) — read that document's "Where this forks from Phase 1"
   section before assuming the two phases share an implementation.
3. [`docs/design/control-plane/microvm-build-backend.md`](docs/design/control-plane/microvm-build-backend.md)
   and
   [`docs/design/control-plane/microvm-backend-integration.md`](docs/design/control-plane/microvm-backend-integration.md)
   — the original planning docs both Phase 1 and Phase 3 build on and in places correct. Still the
   authority on *why* (cost model, PGO motivation) and on Phase 2 (PGO suspend/resume, not started,
   not this task list's concern).

## Status

- Design: done for Phase 1 and Phase 3. Phase 2 (PGO suspend/resume) remains a separate,
  unscheduled later phase per the original docs.
- Implementation: **Phase 1, steps 1–7 of 8 complete.** See below.

## Phase 1 implementation steps

Order matters — each step depends on the previous one compiling and passing tests. Taken verbatim
from `microvm-build-backend-phase1.md`'s own "Implementation order" section.

- [x] **Step 1 — `CellLauncher` abstraction + `FargateCellLauncher` extraction.**
      Commit `04945f1`. New types in `scaleout-build-ecs`: `CellLauncher`, `CellHandle` (sealed:
      `FargateHandle`/`MicroVmHandle`), `CellLaunchRequest`, `CellPollResult`/`CellLaunchState`
      (this module's own vocabulary — deliberately not reusing `-api`'s `CellState`, see the
      javadoc on `CellPollResult` for why), `CellLaunchException`. `FargateCellLauncher` is a pure
      extraction of the existing inline ECS logic — verified byte-identical `RunTaskRequest`/
      `StopTaskRequest` shapes via `FargateCellLauncherTest` (9 tests). `AgentEnvironment.Builder`
      gained `toMap()` alongside the existing `build()`, so a later MicroVM launcher's JSON
      payload can be built from the same accumulated values rather than a second, parallel
      construction path.
      **Verified:** `mvn test -pl scaleout-build-ecs` — 103 tests, 0 failures (was 94 before this
      step).

- [x] **Step 2 — `BuildRecord.CellRecord` + `BuildItems` widening.**
      Done. `CellRecord` gained three nullable fields: `backend` (getter defaults to `"FARGATE"`
      when the stored value is null, so every pre-existing record deserializes unchanged — no
      Jackson default-value annotation needed, the normalization lives in the getter), `microvmId`,
      and `microvmEndpoint`. `BuildItems` gained a new flat `StringSet` attribute
      `ATTR_MICROVM_IDS` (`"microvmIds"`), populated the same way `ATTR_TASK_ARNS` already is —
      only for non-terminal cells, so the reaper only ever sees ids worth stopping.
      New `BuildItemsTest` (plain JUnit 5 — `BuildItems`/`BuildRecord` are plain classes with no CDI
      dependency, so Pattern A per `docs/design/testing/unit-test-strategy.md`, not
      `@QuarkusTest`), 4 tests:
      - `fromItem_deserializesPreExistingRecord_withBackendDefaultingToFargate` — the actual proof
        the design doc calls for: deserializes a fixture `document` JSON shaped exactly like a
        pre-this-change record (no `backend`/`microvmId`/`microvmEndpoint` anywhere in it) and
        asserts `backend == "FARGATE"`, `microvmId == null`, `microvmEndpoint == null`, and that
        the pre-existing `taskArn` field is unaffected.
      - `toItem_writesTaskArnsOnly_whenAllCellsAreFargate` — no behavior change for the
        all-Fargate case; `ATTR_MICROVM_IDS` key is absent entirely, not present-and-empty.
      - `toItem_writesMicrovmIds_forMicrovmBackedCells` — mixed Fargate+MicroVM build writes both
        flat attributes correctly, and the MicroVM fields round-trip through the JSON document too.
      - `toItem_omitsMicrovmIds_whenMicrovmCellIsTerminal` — mirrors the existing terminal-cell
        exclusion `ATTR_TASK_ARNS` already has.
      **Verified:** `mvn -pl scaleout-build-control-plane test` — all tests green, including the
      pre-existing `BuildServiceTest`/`ApplicationBootTest` (confirms the widening didn't disturb
      anything already depending on `CellRecord`). Also ran `mvn -B clean verify` across the full
      12-module reactor — `BUILD SUCCESS`.
      Files touched: `scaleout-build-control-plane/src/main/java/.../store/BuildRecord.java`,
      `.../store/BuildItems.java`,
      `scaleout-build-control-plane/src/test/java/.../store/BuildItemsTest.java` (new file/package
      — none existed for `store` before this step, confirmed by searching first).

- [x] **Step 3 — Agent: `AgentHookServer`, `AgentConfig.fromHookPayload`, `writeStatusJson`.**
      Done. Confirmed first, as the design doc instructed, that `AgentMain` had no HTTP server of
      any kind and that `runWithDirectS3Io` was still reusable unchanged — both still true.
      - `AgentConfig.fromHookPayload(byte[])`: parses the JSON payload into a
        `Map<String, String>` (added `jackson-databind` to this module's pom — inherits the
        version from the parent's `jackson-bom`, no separate pin) and delegates to the existing
        `fromMap()`. This was the simplest-correct option once `CellLaunchRequest.agentEnvironment()`
        turned out (from step 1) to already be the same `Map<String, String>` shape
        `AgentEnvironment.Builder.toMap()` produces — the hook payload is just that map serialized
        as JSON, so `fromMap()`'s existing validation needs no duplication at all.
      - `AgentHookServer` (new class, same package as `AgentMain`/`AgentConfig`/`S3Io` so it can
        reuse the package-private `S3Io` directly): `com.sun.net.httpserver.HttpServer`, no new
        dependency. `/ready` always 200. `/validate` runs `native-image --version` as a cheap
        stand-in synthetic compile and logs-but-does-not-fail on error (image construction should
        not block on prefetch quality). `/run` reads the payload, responds 200, then runs the
        compile on a **non-daemon** thread (`AgentMain.runWithDirectS3Io`, reused verbatim) and
        writes `status.json` afterward. Port defaults to 9000 — a second copy of
        `MicroVmImageManager.AGENT_HOOK_PORT`, documented as such, since `scaleout-build-agent`
        does not depend on `scaleout-build-ecs` (same module-separation reasoning `CellLauncher`'s
        own javadoc gives for the plugin/control-plane split).
      - **Bug caught by the test, not by inspection:** the first `writeStatusJson` attempt tried to
        re-read the produced artifact from the local staging directory after
        `runWithDirectS3Io` returned — but that method's own `finally` block deletes the local
        staging directory (including `output/`) before returning. `describeArtifacts` now lists
        the **already-uploaded S3 objects** instead and streams each one through a `MessageDigest`
        (8 KB buffer, not loaded fully into memory — these are native-image binaries) rather than
        touching local disk at all. This is exactly the kind of defect the task list's own
        "pure extraction" reminder and `HANDOVER.md`'s traps list warn about: it only showed up
        once a real (if fake) compile-and-upload cycle ran end to end, not from reading the code.
      - `status.json` shape matches the design doc exactly: `buildId`, `cell`
        (`"NATIVE/ARM64"`-style, built the same way `CellLaunchRequest.cellId()` does), `state`
        (`SUCCEEDED`/`FAILED`), `exitCode`, `artifacts` (`path`/`sha256`/`sizeBytes`, empty on
        failure), `completedAt`.
      - New `scaleout-build-agent/src/main/resources/microvm/Dockerfile` template per the design
        doc's packaging section — same jar, `CMD` points at `AgentHookServer` instead of
        `AgentMain`.
      - Tests: `AgentHookServerTest` (new — loopback `HttpServer`, this test playing the
        platform's role, against a real SeaweedFS S3 gateway so the `status.json` round trip
        exercises the real wire format rather than a mocked `S3Client`, matching this module's
        existing `AgentMainDirectS3IoTest` convention). Three tests: `/ready` returns 200
        immediately; `/run` returns within ~2s while the fake compile sleeps 300ms in the
        background, then `status.json` appears with `SUCCEEDED`/exit 0/one artifact with a
        non-zero size; a failing fake compile produces `status.json` with `FAILED`/exit 1/empty
        artifacts. Also extended `AgentConfigTest` with three cases for `fromHookPayload` (reads
        the same keys as `fromMap`, applies the same required-field validation, rejects malformed
        JSON naming `runHookPayload`).
      **Verified:** `mvn -pl scaleout-build-agent test` — 25 tests across 5 test classes, 0
      failures (needs Docker; uses testcontainers/SeaweedFS, same as the pre-existing
      `AgentMainDirectS3IoTest`). Also ran `mvn -B clean verify` across the full 12-module
      reactor — `BUILD SUCCESS`.
      Files touched: `scaleout-build-agent/pom.xml` (new `jackson-databind` dependency),
      `.../build/agent/AgentConfig.java`, new
      `.../build/agent/AgentHookServer.java`, new
      `.../build/agent/AgentHookServerTest.java`,
      `.../build/agent/AgentConfigTest.java`, new
      `scaleout-build-agent/src/main/resources/microvm/Dockerfile`.

- [x] **Step 4 — `MicroVmCellLauncher`.**
      Done. Re-verified via `javap` against the real `lambdamicrovms-2.55.11.jar` before writing
      any code, per the task list's own standing reminder — found one real divergence from the
      design doc's sketch:
      - **`RunMicrovmRequest.Builder.runHookPayload` takes a plain `String`, not `SdkBytes`.**
        The design doc's sketch used `SdkBytes.fromUtf8String(...)`; there is no
        `SdkBytes`-accepting overload at all on the real builder. Fixed by passing the JSON string
        directly. Everything else in the sketch — `IdlePolicy` fields, `maximumDurationInSeconds`,
        `egressNetworkConnectors`, `RunMicrovmResponse.microvmId()`/`endpoint()` existing directly,
        `MicrovmState`'s exact 7 values with no `FAILED` — matched exactly.
      - **A second, more structural gap found while implementing `poll()`, not by `javap`:** the
        `CellLauncher` interface's `poll(CellHandle)`/`stop(CellHandle, String)` receive only the
        handle, not the original `CellLaunchRequest` — but `MicroVmCellLauncher.poll()` needs the
        cell's staging path to find `status.json` in S3, and polling happens in a later, possibly
        separate `BuildService` call than `launch()`. Fixed by widening
        `CellHandle.MicroVmHandle` with a third field, `stagingRelativePath`, set once at launch
        time — the handle must be self-sufficient for everything a later call does with it, the
        same reason `FargateHandle` already carries the full `taskArn` rather than requiring the
        caller to reconstruct it. (No existing code outside this step's own new files constructed
        a `MicroVmHandle` yet, confirmed by searching first, so this was a safe widen rather than a
        breaking change.)
      - New `MicroVmSettings` record (`imageArn`, `imageVersion`, `executionRoleArn` — reused from
        the existing admin image-build role per the design doc's "Config additions" reasoning,
        `vpcEgressConnectorArn`), independent of `EcsClusterSettings` since that record is read by
        `FargateCellLauncher` only.
      - `launch()`: rejects non-`ARM64` architectures with a `CellLaunchException` naming the
        actual architecture — this module's own dispatch bug if it's ever hit, not a user error.
        Builds `RunMicrovmRequest` explicitly (not via the `Consumer<Builder>` convenience
        overload) to match `MicroVmImageManager`'s existing convention and keep the request
        directly capturable/assertable in tests the same way that class's own test already does
        for `CreateMicrovmImageRequest`.
      - `poll()`: `status.json` is the primary signal (read via S3 `GetObject`, mapped to
        `SUCCEEDED`/`FAILED` with exit code); falls back to `GetMicrovm` only when absent, to
        distinguish "still compiling" (`PENDING`/`RUNNING`) from "the platform silently killed it"
        (`TERMINATED`/`TERMINATING`, surfaced as `FAILED` with the state reason) from an
        unexpected `SUSPENDED`/`SUSPENDING` (Phase 1 never suspends, so this is reported as
        `FAILED` with a message saying so, not treated as transient).
      - `stop()`: calls `TerminateMicrovm`, tolerant of "already terminated" the same way
        `FargateCellLauncher.stop()` already tolerates "already stopped" — logs and swallows
        rather than throwing.
      - Added `jackson-databind` to `scaleout-build-ecs`'s pom (version from the already-managed
        `jackson-bom`) for the `runHookPayload` JSON body and the `status.json` round trip. Hit
        the `--` XML-comment trap from `HANDOVER.md`'s own traps list a second time while writing
        the pom comment — worth noting since this is now twice in this task list's own session.
      - Tests: new `MicroVmCellLauncherTest`, mocking `LambdaMicrovmsClient`/`S3Client` the same
        way `MicroVmImageManagerTest` already mocks `LambdaMicrovmsClient` for the admin flow (9
        tests: rejects `X86_64`; `RunMicrovmRequest` shape incl. `maximumDurationInSeconds` =
        (timeout+margin)×60 and the JSON `runHookPayload` content; wraps a launch `SdkException`
        as `CellLaunchException`; `poll()` reading `SUCCEEDED`/`FAILED` from `status.json`;
        `poll()` falling back to `GetMicrovm` for both `RUNNING` and `TERMINATED` — the latter
        being the actual proof that `MicrovmState`'s missing `FAILED` value is handled correctly;
        `stop()` sending the right request and tolerating an already-terminated error).
      **Verified:** `mvn -pl scaleout-build-ecs test` — 9/9 new tests green, full module suite
      (including `FargateCellLauncherTest` and everything from step 1) unaffected by the
      `CellHandle` widening. Also ran `mvn -B clean verify` across the full 12-module reactor —
      `BUILD SUCCESS`.
      Files touched: new `scaleout-build-ecs/src/main/java/.../ecs/MicroVmSettings.java`, new
      `.../ecs/MicroVmCellLauncher.java`, `.../ecs/CellHandle.java` (widened `MicroVmHandle`),
      `scaleout-build-ecs/pom.xml` (new `jackson-databind` dependency), new
      `scaleout-build-ecs/src/test/java/.../ecs/MicroVmCellLauncherTest.java`.

- [x] **Step 5 — `BuildService` dispatch changes.**
      Done, with one sequencing deviation from the task list's own ordering: `BuildService`'s
      dispatch needs `config.microVm().enabled()`/`imageArn()`/`imageVersion()`/
      `vpcEgressConnectorArn()` to decide which launcher to use, so those four config fields (part
      of what the task list calls step 7's "Config additions") were pulled forward into this step
      rather than left for later — the CDK/IAM wiring that actually deploys them remains step 7's
      job. Confirmed `BuildService` had never adopted the `CellLauncher` abstraction from step 1 at
      all before this step — it still inlined `TaskDefinitionRegistrar`/`EcsTaskLauncher` calls
      directly, exactly as `microvm-build-backend-phase1.md` describes "today."
      - `start()`: per-cell loop now builds a `CellLaunchRequest` and calls
        `cellLauncher(architecture).launch(request)`. New `cellLauncher(Architecture)`: returns
        `MicroVmCellLauncher` for `ARM64` only when `enabled()` is true and all three MicroVM
        settings are present, `FargateCellLauncher` otherwise. New `applyHandle(CellRecord,
        CellHandle)` translates the returned handle into the cell's stored `backend`/`taskArn` or
        `backend`/`microvmId`/`microvmEndpoint` fields — the one place this module's `CellHandle`
        vocabulary meets `BuildRecord.CellRecord`'s stored shape. Minor, intentional behavior
        note: `cell.setTaskDefinitionArn(...)` is no longer called after this refactor, confirmed
        by searching first that nothing anywhere reads `CellRecord.getTaskDefinitionArn()` back —
        it was write-only before this change too.
      - `refreshFromEcs()` → renamed `refreshCellStates()` (updated its one other call site in
        `LogStreamResource`). Splits non-terminal cells by stored `backend`: Fargate cells go
        through one batched `FargateCellLauncher.pollBatch()` call (unchanged shape/batching from
        before), MicroVM cells are polled one `MicroVmCellLauncher.poll()` call each (no batch
        `GetMicrovm` exists, confirmed absent via `javap` in step 4). New
        `applyPollResult(BuildRecord, CellRecord, CellPollResult)` is the one place this module's
        `CellLaunchState` is mapped onto the control plane's own `CellState` — carries forward the
        existing exit-124→timeout-message translation for Fargate cells unchanged, and passes a
        MicroVM's own `CellPollResult.failed()` reason through as-is (a MicroVM has no
        `coreutils timeout` wrapper, so there's nothing Fargate-specific to translate).
      - `stopRunningCells()`: dispatches on each cell's **stored** `backend` field via a
        reconstructed `CellHandle` (`cellHandleOf(CellRecord)`), not current config — a config flip
        mid-build must not change how an in-flight build's cells are stopped, per the design doc.
      - Constructor now also takes `LambdaMicrovmsClient`/`S3Client` — both were already
        `@ApplicationScoped` CDI producers in `AwsClientProducer` (confirmed by reading it first;
        `LambdaMicrovmsClient` was already there for the admin image-build flow), so no new
        producer was needed.
      **Verified:** ran `BuildServiceTest` immediately after the refactor with zero test changes —
      all 17 pre-existing tests passed unmodified against the new dispatch logic, a strong signal
      the Fargate path's behavior didn't shift. Added MicroVM-dispatch coverage in a **separate**
      new test class, `BuildServiceMicrovmDispatchTest`, rather than as more `@Test` methods in
      `BuildServiceTest` — found the hard way why: the first attempt set
      `%test.scaleout.micro-vm.enabled=true` globally in `application.properties`, which broke the
      pre-existing `start_launches_one_ecs_task_per_cell` (its `NATIVE/ARM64` cell silently
      rerouted to an unstubbed `MicroVmCellLauncher`, throwing a `NullPointerException` inside
      `BuildService`'s own catch-and-mark-`FAILED` block, so the test failed on a Fargate call
      count instead). That failure is itself a live demonstration of the design doc's "Backend
      selection: by architecture, not configuration" warning — enabling MicroVM is a
      deployment-wide behavior change, not a per-test toggle. Fixed by reverting the global
      property and using a class-scoped `@TestProfile` instead, so only
      `BuildServiceMicrovmDispatchTest` runs with `scaleout.micro-vm.enabled=true` (4 tests: mixed
      x86_64+arm64 build dispatches one cell to each backend; a launched MicroVM cell's id/endpoint
      land on the record with no `taskArn`; `cancel()` calls `TerminateMicrovm` for a MicroVM cell
      and never touches ECS; `status()` reads `status.json` from S3 for a MicroVM cell and never
      calls `DescribeTasks`). Final count: `BuildServiceTest` 17/17 unmodified,
      `BuildServiceMicrovmDispatchTest` 4/4 new, full module suite green, and
      `mvn -B clean verify` across the full 12-module reactor — `BUILD SUCCESS`.
      Files touched: `scaleout-build-control-plane/src/main/java/.../service/BuildService.java`,
      `.../config/ControlPlaneConfig.java` (four new `MicroVm` fields), `.../resource/
      LogStreamResource.java` (one call-site rename), `scaleout-build-control-plane/src/main/
      resources/application.properties` (new config entries + `%test.` overrides for the fake
      image/version/connector values), new
      `scaleout-build-control-plane/src/test/java/.../service/BuildServiceMicrovmDispatchTest.java`.

- [x] **Step 6 — Reaper dispatch change.**
      Done. `BuildReaperHandler.reapOne` now reads both the pre-existing flat `taskArns`
      attribute and the new flat `microvmIds` attribute (added in step 2's `BuildItems` widening)
      and dispatches to each: `ecs.stopTask()` per task ARN (unchanged), `microvms.terminateMicrovm()`
      per MicroVM id (new). Both loops share the same tolerance for an already-stopped/terminated
      resource — log-and-continue, since marking the build `EXPIRED` regardless is what actually
      matters (confirmed this reasoning already existed for the ECS loop before touching it).
      - Added `lambdamicrovms` to the reaper's own pom (confirmed via `grep` it wasn't already
        there) and a `LambdaMicrovmsClient` constructor parameter alongside the existing
        `EcsClient` — this reaper runs as its own Lambda with its own role, so it needs its own
        client the same way it already has its own copy of the `ATTR_TASK_ARNS` string literal
        rather than depending on the service's `BuildItems` class (confirmed that reasoning is
        deliberate by reading `BuildItems.ATTR_TASK_ARNS`'s own javadoc first).
      - The `UpdateItem` call's `REMOVE` clause now removes both `taskArns` and `microvmIds`
        (`REMOVE #t, #m`) rather than just `taskArns`, so a reaped build's item doesn't keep a
        stale `microvmIds` set around after being marked `EXPIRED`.
      - **Incidental fix found while writing the new tests, not part of this step's own scope:**
        switched `dynamo.updateItem(...)` from the `Consumer<Builder>` lambda overload to building
        an explicit `UpdateItemRequest` directly — same category of fix as `MicroVmCellLauncher`
        in step 4 (`Consumer` overloads can't be captured/asserted on directly with
        `ArgumentCaptor`, and this step's new `updateExpressionRemovesBothFlatAttributes` test
        needed to assert on the actual expression string). No functional change; purely a
        testability fix, verified by confirming all pre-existing reaper tests still pass unmodified
        except for updating their own `verify(...)` calls to match the new overload.
      - Tests: extended `BuildReaperHandlerTest` with 4 new cases (reaps a MicroVM-only build;
        reaps a mixed Fargate+MicroVM build, confirming both loops run independently; marks the
        build `EXPIRED` even when `TerminateMicrovm` fails, mirroring the existing StopTask-failure
        test; the `UpdateItem` expression removes both flat attributes). All 6 pre-existing tests
        updated only to inject the new `LambdaMicrovmsClient` mock and the renamed `verify(...)`
        matcher — no behavioral assertions changed.
      **Verified:** `mvn -pl scaleout-build-control-plane-reaper test` — 10/10 (6 pre-existing + 4
      new). Also ran `mvn -B clean verify` across the full 12-module reactor — `BUILD SUCCESS`.
      Files touched: `scaleout-build-control-plane-reaper/pom.xml` (new `lambdamicrovms`
      dependency), `.../reaper/BuildReaperHandler.java`,
      `.../reaper/BuildReaperHandlerTest.java`.

- [x] **Step 7 — Config + CDK additions.**
      Done. The four `ControlPlaneConfig.MicroVm` fields (`enabled()`, `imageArn()`,
      `imageVersion()`, `vpcEgressConnectorArn()`) were actually added back in step 5, since
      `BuildService.cellLauncher(Architecture)` needed them to exist before it could compile —
      this step is the remaining CDK/IAM wiring that actually deploys values into them.
      - **Resolved open question 2** (shared vs. independent gate for build-execution, left
        unsettled by the design doc): build-execution MicroVM support is **nested inside** the
        existing `if (!microvmAdminRoleArn.isBlank())` block, gated by its own separate
        `microvmEnabled` boolean context key rather than reusing `microvmAdminRoleArn` as the
        on/off switch directly. Decided this way for a concrete reason, not just a preference:
        `executionRoleArn()` is reused unchanged from the admin flow's own `microvmExecutionRole`
        (per the design doc's own "Config additions" reasoning), and that role object only exists
        inside the admin block — there is nothing for an independently-gated build-execution
        branch to reuse unless the admin block already ran. This still gets the deployment
        flexibility the open question wanted: `microvmAdminRoleArn` set with `microvmEnabled`
        unset/false builds and qualifies an image with zero builds routed to it yet; flipping
        `microvmEnabled=true` (plus the three value context keys) later routes `arm64` cells to it
        without touching the admin wiring.
      - New CDK context keys: `microvmEnabled` (boolean string, default `false`),
        `microvmImageArn`, `microvmImageVersion`, `microvmVpcEgressConnectorArn` — same
        `tryGetContext`-with-blank-fallback pattern the existing `microvmAdminRoleArn` already
        uses, read inside the admin block after the existing role/bucket/env wiring.
      - Service function gains `lambda-microvms:RunMicrovm`/`GetMicrovm`, scoped to the same
        image-name ARN resource the existing admin actions already use. Granted whenever the admin
        gate is open, not conditionally on `microvmEnabled`'s current value — a config flip to
        start routing builds to MicroVMs should not also require a redeploy just to grant the IAM
        action it needs; `ControlPlaneConfig.MicroVm.enabled()` is still what gates actual dispatch
        at runtime (confirmed in step 5), so an unused grant here is not a live behavior change.
      - Four new env vars on the service function:
        `SCALEOUT_MICROVM_ENABLED`/`IMAGE_ARN`/`IMAGE_VERSION`/`VPC_EGRESS_CONNECTOR_ARN`.
      - Reaper function gains its own separate `lambda-microvms:TerminateMicrovm` grant (confirmed
        it previously held only `ecs:StopTask`/`ecs:DescribeTasks`) — same action already granted
        to the service function above, granted a second time because the reaper is its own
        function with its own role, same reasoning the design doc gives.
      - **Real pre-existing bug found and fixed while verifying this step, unrelated to any new
        code**: `MicrovmArtifactsBucket`'s `Bucket` construct and a `CfnOutput` both used the
        construct id `"MicrovmArtifactsBucket"` in the same scope, which CDK rejects as a
        duplicate id. This had existed since the admin-flow block was first written and was never
        caught because `verify-synth.sh` never sets `microvmAdminRoleArn` — it only exercises the
        baseline `runtimeMode`/`development` context. Found by actually synthesizing with
        `microvmAdminRoleArn` set (to verify this step's own new code), not by inspection. Fixed by
        renaming the output id to `MicrovmArtifactsBucketName`.
      **Verified:** `./scripts/verify-synth.sh` still passes both deployment modes unchanged (its
      assertions never touch the MicroVM block, so this confirms the baseline path is unaffected).
      Additionally ran `cdk synth` directly with `microvmAdminRoleArn`/`microvmEnabled`/
      `microvmImageArn`/`microvmImageVersion`/`microvmVpcEgressConnectorArn` all set (the actual
      new code path — `verify-synth.sh` does not exercise it) and inspected the resulting
      template: all four env vars present with the right values, the service function's policy
      has both the admin actions and the new `RunMicrovm`/`GetMicrovm` grant scoped correctly, the
      reaper function has its own independent `TerminateMicrovm` grant. Also ran
      `mvn -B clean verify` across the full 12-module reactor — `BUILD SUCCESS` (one transient
      `Port already bound: 8081` `@QuarkusTest` boot failure on the first attempt, from two forked
      surefire JVMs racing to bind the same hardcoded test port; reran immediately and it passed
      cleanly — recorded here since it looked at first like a regression from this step's changes
      and was not).
      Files touched:
      `scaleout-build-control-plane-infra/src/main/java/.../infra/ControlPlaneInfraStack.java`.

- [ ] **Step 8 — End-to-end verification against real AWS.**
      **Open question 1 (regional availability) resolved**: confirmed via
      `ListManagedMicrovmImages` that Lambda MicroVMs are available in `us-east-1` (this
      project's actual deployed region, confirmed by reading the live stack's outputs) and in
      `eu-west-1`/`eu-central-1`/`us-west-2`.

      **Open question 3 (VPC egress connector) resolved as "not required," not just answered**:
      the design doc, `tasklist.md`'s own step 7 entry, `MicroVmSettings`, and
      `ControlPlaneConfig.MicroVm` all originally treated `vpcEgressConnectorArn` as required
      alongside `imageArn`/`imageVersion`. That was wrong, caught only once actually checked
      against AWS's own documentation (Lambda MicroVMs' "Networking" page: *"By default, MicroVMs
      have public internet access. You can create a customer-managed VPC egress connector to
      route outbound traffic through your VPC instead"*) and confirmed via `javap` that
      `RunMicrovmRequest.egressNetworkConnectors()` is backed by an `SdkAutoConstructList`, not a
      required field. Phase 1's agent needs only S3 and CloudWatch Logs, both public AWS service
      endpoints reachable over the default public path via the execution role's IAM permissions
      alone — there was never a private resource to reach, so there was never a connector
      requirement. Fixed retroactively in `MicroVmSettings`, `MicroVmCellLauncher.launch()` (omits
      `egressNetworkConnectors` entirely when unconfigured, rather than sending a blank value),
      `ControlPlaneConfig.MicroVm`, and `BuildService.cellLauncher()`'s gating (now requires only
      `imageArn`/`imageVersion`/`executionRoleArn`). New test
      `launch_omitsEgressNetworkConnectors_whenNoneConfigured` in `MicroVmCellLauncherTest`.
      Re-ran the full 12-module reactor after the fix — `BUILD SUCCESS`, no regressions.

      Deployed a genuinely fresh stack to `us-east-1` (account `864899852480`) per explicit user
      instruction: destroyed the prior stack, deleted the `RemovalPolicy.RETAIN`'d staging bucket
      (848 object versions) and `scaleout-builds` table (23 records) rather than importing them,
      then `./scripts/deploy-local.sh --native --yes`. `CREATE_COMPLETE`. Ran a real end-to-end
      build via `docs/examples/scaleout-build-example-app` against the new endpoint — both
      `x86_64` and `arm64` cells launched on Fargate, compiled, and produced genuine distinct ELF
      binaries (`file` confirms `x86-64` and `ARM aarch64`), attached to the reactor. This is the
      Fargate baseline's own regression check, now proven against a live deployment carrying every
      Phase 1 code change through step 7.

      Next: provision the MicroVM admin role, build and publish the `scaleout-build-agent`
      MicroVM image via the now-unblocked admin flow, enable `scaleout.micro-vm.enabled`, and run
      one real `arm64` build routed through `MicroVmCellLauncher` end to end.

      **Three more real bugs found by actually deploying the admin flow for the first time ever**
      (confirmed via `grep` that `microvmAdminRoleArn` had never been set on any deployment of
      this stack before this session — the admin block existed in code but had never actually
      run):
      1. `Role.Builder.description(...)` for `MicrovmBuildRole` contained an em dash. IAM role
         descriptions are restricted to a narrow ASCII+Latin-1 character set; CloudFormation
         rejected the role creation outright with a regex validation error. Fixed by replacing the
         em dash with a plain hyphen.
      2. **Both MicroVM roles trusted `lambda-microvms.amazonaws.com`, which is not a valid IAM
         service principal** — IAM rejected it with "Invalid principal in policy." Confirmed
         against AWS's own documentation (Lambda MicroVMs' "Security and permissions" page):
         "Both roles require a trust policy that allows the Lambda service principal
         `lambda.amazonaws.com` to perform `sts:AssumeRole` and `sts:TagSession`." Fixed the
         principal, and added the missing `sts:TagSession` statement as a second trust-policy
         statement on each role (`role.getAssumeRolePolicy().addStatements(...)`, same pattern
         CDK's own docs use for EMR's `SetSourceIdentity`/`TagSession` case) — CDK's
         `assumedBy(ServicePrincipal)` alone only grants `sts:AssumeRole`.
      3. **Every MicroVM-related IAM action in this stack used the wrong namespace**:
         `lambda-microvms:RunMicrovm`/`GetMicrovm`/`CreateMicrovmImage`/`GetMicrovmImage`/
         `GetMicrovmImageBuild`/`TerminateMicrovm` should all be `lambda:*`, confirmed against
         AWS's own "IAM permissions reference" table on the same documentation page (e.g.
         `lambda:CreateMicrovmImage`, `lambda:RunMicrovm`, `lambda:TerminateMicrovm` — there is no
         `lambda-microvms:` action namespace at all). This affected both this step's own new
         `RunMicrovm`/`GetMicrovm` grant and the pre-existing admin actions from the earlier
         (unverified) version of this block. Fixed with a project-wide `sed` across the one file
         these literals live in; confirmed via `grep` that no other file in the reactor
         (production code; design docs are prose, left as historical record) used the wrong
         namespace.

      None of this was caught by `./scripts/verify-synth.sh`, because that script never sets
      `microvmAdminRoleArn` and therefore never synthesizes this block at all — confirmed by
      reading the script first. All three were found only by actually running
      `cdk deploy -c microvmAdminRoleArn=...` against the real account for the first time, which
      is exactly the category of defect `HANDOVER.md`'s own traps list already warns about for
      this project's native-mode Lambda ("a JVM build cannot detect them... found by deploying").
      Deployed successfully after the fixes: `MicrovmArtifactsBucket`
      (`scaleout-build-microvm-artifacts-864899852480-us-east-1`), `MicrovmBuildRole`, and
      `MicrovmExecutionRole` all `CREATE_COMPLETE`.

      **Two more real bugs found by actually triggering a real image build** (POST
      `/admin/microvm-images` via the CLI, S3 code artifact uploaded first):
      4. `CreateMicrovmImage` cannot be scoped to the target image's ARN — the image does not
         exist yet at call time, so there is nothing for a resource-scoped statement to match,
         and AWS evaluates the call against `Resource: "*"`. Confirmed against AWS's own
         least-privilege example policy on the same documentation page: it places
         `lambda:CreateMicrovmImage` in a separate `Resource: "*"` statement alongside
         `lambda:ListMicrovms`/`ListMicrovmImages`, not in the resource-scoped statement with
         `GetMicrovmImage`/`TerminateMicrovm`/etc. Split into its own `Resource: "*"` statement;
         the actions that do name an existing image stayed scoped to the fixed image name.
      5. **`iam:PassRole` for `microvmBuildRole` was missing entirely** — only
         `microvmExecutionRole` had a `PassRole` grant, so the first real
         `CreateMicrovmImage` call (which passes `microvmBuildRole`) failed with
         `"not authorized to perform: iam:PassRole on resource: .../MicrovmBuildRole"`. Also
         caught the stray `"lambda-microvms.amazonaws.com"` in this same statement's
         `iam:PassedToService` condition — same wrong-principal mistake as bug 2, missed by the
         earlier `sed` fix because that only touched `lambda-microvms:`-prefixed action strings,
         not this condition value. Fixed by granting `PassRole` for both roles together, scoped to
         the corrected `lambda.amazonaws.com` principal.
      6. **A third IAM action was needed that nothing in the design doc, the task list, or AWS's
         own IAM permissions reference table mentioned at all**: `lambda:PassNetworkConnector` on
         the AWS-managed `arn:aws:lambda:<region>:aws:network-connector:aws-network-connector:
         INTERNET_EGRESS` ARN. `MicroVmImageManager.triggerBuild` passes no
         `egressNetworkConnectors` to `CreateMicrovmImage`, and the platform defaults the
         image-build-time connector to this fixed, AWS-owned resource rather than leaving it
         genuinely unset — confirmed by reading the exact resource ARN out of the real IAM denial
         error, not by finding it documented anywhere in advance. Granted
         `lambda:PassNetworkConnector` on that specific ARN.

      After all six fixes, `images build --artifact s3://.../agent-microvm-1.0.1.zip` (code
      artifact: the `Dockerfile` from `scaleout-build-agent/src/main/resources/microvm/`, itself
      corrected below, plus the built `agent.jar`, zipped and uploaded via a presigned PUT)
      succeeded: `202 Accepted`, `imageVersion: "1.0"`, `state: CREATING`.

      **The MicroVM Dockerfile template itself (written in step 3) also had two real defects,
      found before even attempting the build above, by reading AWS's "Create your first Lambda
      MicroVM" page directly rather than trusting the design doc's own packaging section**:
      - `FROM BASE_IMAGE_PLACEHOLDER` was wrong in a way that would have failed differently:
        the managed MicroVM base image is supplied separately via `--base-image-arn` in the
        `CreateMicrovmImage` call (already correct in `MicroVmImageManager.triggerBuild`) — `FROM`
        in this Dockerfile is an ordinary *application-layer* base image, not a reference to the
        managed base at all. A first attempt at fixing this swapped in a plain JRE Alpine image,
        which is also wrong: the agent runs a real `native-image` compile
        (`AgentHookServer` → `AgentMain.runWithDirectS3Io` → `NativeImageBuildExecutor`,
        unchanged from step 3), and a bare JRE has no `native-image` binary at all. Settled on
        `quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25` — the same arm64 Mandrel
        builder image this project's Fargate/ECS path already uses, confirmed required to be the
        arm64 variant specifically because Lambda MicroVMs run ARM64 only.
      - Added the matching non-root user (`USER 1001:1001`) and `COPY --chown` the Fargate
        Dockerfile already uses for the same base image, for consistency rather than by
        necessity.

      Next: poll `images status scaleout-build-agent` until `CREATED` (~10–15 min), then enable
      `scaleout.micro-vm.enabled` with the resulting image ARN/version and run one real `arm64`
      build routed through `MicroVmCellLauncher`.

      **Seventh bug, in the CLI's own default rather than the CDK stack**: `images status` with
      no argument defaults to the bare image name `scaleout-build-agent`, but
      `MicroVmImageManager.getImageStatus` passes that string straight through to
      `GetMicrovmImage` as `imageIdentifier`, and the real API rejects a bare name with
      `ValidationException: Invalid ARN format: scaleout-build-agent` — confirmed via the
      control-plane Lambda's own CloudWatch logs, since the HTTP 500 response body is
      deliberately opaque (`{"details": "Error id ...", "stack": ""}`, no exception detail
      leaked to the caller). The full ARN
      (`arn:aws:lambda:<region>:<account>:microvm-image:scaleout-build-agent`) works. Not fixed
      in this session — recorded here rather than silently worked around, since the CLI's own
      `StatusCommand.imageIdentifier` default value (`"scaleout-build-agent"`) is the thing that
      needs to change, and that is CLI UX scope, not Phase 1 build-execution scope.

      **Eighth bug, the actual image build's own first real failure**: both build attempts
      failed with `"Ready hook invocation timed out after PT2M"` (confirmed via
      `ListMicrovmImageBuilds`, which needed a `buildId` the status endpoints don't surface —
      found that operation only by searching the SDK directly). Root cause, confirmed by
      `docker inspect`-ing the real base image: `quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25`
      ships `ENTRYPOINT ["native-image"]` with an empty `CMD`. The Dockerfile template from step 3
      only set `CMD` to the `java -cp ...` invocation, never overriding `ENTRYPOINT` — so Docker
      appended that `CMD` as *arguments to `native-image`* instead of replacing the entrypoint.
      The container actually ran `native-image -cp /home/quarkus/agent.jar java
      ai.codriverlabs...`, nonsense arguments, which silently did nothing resembling starting an
      HTTP server. Confirmed via CloudWatch: zero log events for the build, because the process
      that would have logged anything never ran. Fixed by overriding `ENTRYPOINT` explicitly in
      the Dockerfile rather than relying on `CMD` alone.

      Verified locally before resubmitting (`docker build --platform linux/arm64` +
      `docker run`, under QEMU emulation on this x86_64 host): the fixed image's agent does start
      and does answer `POST /ready` with 200 — but only after a real test went through several
      rounds of false negatives first, worth recording so the next person does not repeat them.
      A `-d` (detached) run showed the container `running` with zero captured log output and no
      listening socket in `/proc/net/tcp` even after 5–8 seconds, which looked exactly like a
      second startup failure. It was not: a **foreground** run with a 10 s timeout showed the
      "listening on port 9000" log line appear right at the boundary, and a detached run given a
      full 20 s before testing logged normally and answered `/ready` with `HTTP_200`. QEMU
      user-mode binary translation makes JVM startup markedly slower than native execution, and
      this sandbox's Docker log driver appears not to flush a detached container's output until
      some threshold is crossed — neither of which will apply on the real platform, which runs
      genuine ARM64 hardware, not emulation. Deleting the artifact ZIP and retriggering too early
      would have reproduced the same false failure against the real platform, so the lesson is
      specifically "wait longer before concluding failure," not "the Dockerfile fix didn't work."

      **Ninth, operational finding**: `scaleout-build-agent` is a **singleton image name per
      account** — a second `CreateMicrovmImage` call for the same name fails outright with "A
      MicroVM image with the name 'scaleout-build-agent' already exists," rather than creating a
      new version the way the design doc's own `ImageStatus`/`AdminResource` javadoc implies
      ("Each call to `triggerBuild` creates a new version; the platform auto-assigns the version
      number"). That claim is not accurate for a *failed* existing image either — confirmed by
      hitting the "already exists" error against an image sitting in `CREATE_FAILED`, not just an
      active one. `MicroVmImageManager` has no `updateBuild`/`UpdateMicrovmImage` method at all,
      so there is currently no supported retry path through the admin API once a build fails;
      the only way forward in this session was deleting the failed image directly via AWS
      credentials (`DeleteMicrovmImage`, outside the service's own IAM grants — a one-off cleanup
      action, not something the control plane can do for itself) and retriggering a fresh
      `CreateMicrovmImage`. Worth a real fix later (an `UpdateMicrovmImage`-backed retry path in
      `MicroVmImageManager`/`AdminResource`), out of scope for finishing Phase 1 verification now.

      Resubmitted with the corrected Dockerfile: `202 Accepted`, `imageVersion: "1.0"`,
      `state: CREATING`.

      **Tenth bug, the actual next real failure once the `/ready` endpoint became reachable**:
      `"Ready hook check failed: the application returned a client error (HTTP 4xx) response"` —
      a different failure than the earlier timeout, confirming the `ENTRYPOINT` fix worked and
      the server now actually starts. Root cause: `AgentHookServer.start()` registered contexts
      at the bare `/ready`, `/validate`, `/run` paths, but AWS's own documentation (Lambda
      MicroVMs' "MicroVM images" and "Running and using MicroVMs" pages, read directly rather
      than assumed from the design doc's own prose) states every hook listens at the fixed prefix
      `/aws/lambda-microvms/runtime/v1/<hook-name>`, not the bare name. The platform's `/ready`
      check therefore hit this server's own HTTP 404 for a path with no registered context,
      surfaced one layer up as a generic "4xx client error." Fixed by registering all three
      contexts under the correct prefix and updating `AgentHookServerTest`'s own POST paths to
      match (3/3 tests still green). Verified again with a full local Docker round-trip
      (`docker build --platform linux/arm64` + `docker run`, waiting the full ~20s this
      sandbox's QEMU emulation needs) before resubmitting: `curl -X POST
      http://localhost:.../aws/lambda-microvms/runtime/v1/ready` now returns `HTTP_200`.
      Deleted the failed image again and resubmitted a fresh `CreateMicrovmImage` with the
      corrected artifact: `202 Accepted`, `state: CREATING`. **Polled to completion:
      `state: CREATED`, `active: true`** — the MicroVM image build itself is now proven working
      end to end, ten real bugs later, none of which were visible from reading the code or the
      design doc alone.

      Next: enable `scaleout.micro-vm.enabled` with this image's ARN/version, run one real
      `arm64` build routed through `MicroVmCellLauncher`, confirm it produces a working binary.

      **Eleventh bug, found by the first real end-to-end build against the enabled MicroVM
      path**: `RunMicrovm` also needs `lambda:PassNetworkConnector`, this time on a *different*
      AWS-managed connector — `arn:aws:lambda:<region>:aws:network-connector:aws-network-connector:
      HTTP_INGRESS`, not `INTERNET_EGRESS` (that one is specific to `CreateMicrovmImage`, bug 6
      above). `MicroVmCellLauncher.launch()` sets no ingress connector at all, deliberately — see
      its own javadoc on why the control plane never calls into a MicroVM — yet the platform
      still defaults the runtime connector to this fixed resource rather than running with no
      ingress path configured. Confirmed by the exact resource ARN in the real `RunMicrovm`
      denial, the same way bug 6 was confirmed; neither default is documented anywhere this phase's
      design or implementation touched, only surfaced by actually calling the real APIs end to
      end. Fixed by granting `lambda:PassNetworkConnector` on this second fixed ARN too. Worth
      noting for whoever next touches this: there may be further AWS-managed connectors the
      platform defaults to for other operations not yet exercised in this session (e.g.
      `SHELL_INGRESS`, mentioned on the same documentation page for shell access, never used by
      this phase at all) — treat "the exact set of default connectors this account needs
      permission to pass" as still potentially incomplete, not closed by these two.

      **Twelfth bug, and the real design-level one**: `RunMicrovm` launched successfully
      (`NATIVE/ARM64 -> RUNNING` observed in the plugin's own output), but the cell then failed
      with `"MicroVM terminated before writing a status report (stateReason: MicroVM exceeded
      maximum lifetime.)"`. `GetMicrovm` on the actual MicroVM showed `terminatedAt - startedAt`
      of **64 seconds** — not anywhere near the 2400s `maximumDurationInSeconds` that was
      correctly set — with `idlePolicy.maxIdleDurationSeconds: 60`. Root cause: this launcher's
      original `MAX_IDLE_DURATION_SECONDS = 60`, reasoned as "terminate promptly once the `/run`
      hook returns — Phase 1 never suspends," is wrong about what "idle" means on this platform.
      Confirmed against AWS's own `IdlePolicy` documentation: *"Idle time is measured by inbound
      traffic through the MicroVM proxy endpoint."* Once `/run` returns 200 (per the fork-and-
      return model this phase already uses correctly), the actual compile runs on a background
      thread generating **zero** further HTTP traffic to the endpoint — exactly the condition the
      platform calls idle. A 60-second idle budget does not wait for the compile; it starts
      counting the instant the real work begins, and with `autoResumeEnabled(false)` a suspend
      here has no resume path, so it is a termination with extra steps. Fixed by setting
      `maxIdleDurationSeconds` to the same value as `maximumDurationInSeconds` (both derived from
      the compile budget plus margin), so idle and max-lifetime never race each other for the
      wrong reason — whichever fires first now does so because the compile either finished or
      hung, not because of an internally inconsistent policy. Updated
      `MicroVmCellLauncherTest`'s own assertion to match (still a pure unit test against a mocked
      SDK client — this specific defect was only reachable by a real `RunMicrovm` call, since the
      mock never modeled the platform's actual idle-reaping behavior; recording this as a gap the
      unit test suite cannot close by itself, not a reason to distrust the suite).

      **Thirteenth bug**: with the idle-duration fix deployed, a real end-to-end build's `ARM64`
      cell ran a genuinely full 17 minutes (`17:44:21` to `18:01:29`) and `GetMicrovm` reported
      `stateReason: "Success."` — the platform's own verdict that the MicroVM completed cleanly,
      not idle-reaped or killed. But the plugin itself still failed the build, and S3 never
      received a `status.json` for the ARM64 cell at all (only the three staged input files were
      present, no `output/` prefix, nothing). The deployed `/scaleout-build/build-agent` log
      group had all 65 events from the `X86_64`/Fargate cell and **zero** from the ARM64 MicroVm
      cell — not an empty agent, an agent whose output was never captured anywhere this launcher
      could see. Root cause, confirmed against AWS's own `Monitoring` documentation page: Lambda
      MicroVMs only forwards runtime stdout/stderr to CloudWatch Logs when (a) a logging
      configuration is actually set on `RunMicrovmRequest` — the original `MicroVmCellLauncher`
      never set one at all — and even then, (b) *"without an execution role [holding]
      `logs:CreateLogGroup`, `logs:CreateLogStream`, and `logs:PutLogEvents` permissions,
      application stdout and stderr are not forwarded to CloudWatch at runtime."* With no
      `.logging(...)` set, the SDK's documented default target is `/aws/lambda-microvms/<image-
      name>` — confirmed via `DescribeLogGroups` with that prefix that **this group did not
      exist at all** in the account, because `microvmExecutionRole` was never granted
      `logs:CreateLogGroup` (its policy only has `CreateLogStream`/`PutLogEvents` on the existing
      `agentLogGroup`, added for the Fargate-shared group, which was never the default target in
      the first place). The agent's actual compile output is lost permanently for this run — there
      is no second place to look. Fixed in two places: (1) `MicroVmSettings` gained a
      `logGroupName` field (reusing `ControlPlaneConfig.Ecs.logGroupName()` — the same
      `agentLogGroup` already shared with Fargate, rather than provisioning or granting access to
      a second group), and (2) `MicroVmCellLauncher.launch()` now explicitly sets
      `.logging(Logging.builder().cloudWatch(CloudWatchLogging.builder().logGroup(...)
      .logStream(request.cellId())...)...)` on every `RunMicrovmRequest`, pointing at that
      existing group so the IAM grant `microvmExecutionRole` already has
      (`CreateLogStream`/`PutLogEvents`, no `CreateLogGroup` needed since the target group already
      exists) is sufficient without any CDK/IAM change. Each cell's log stream is named after its
      `cellId()` (e.g. `NATIVE/ARM64`), mirroring the Fargate path's own per-task
      `awslogs-stream-prefix` convention, so both backends' output lands in one group,
      distinguishable by stream. **This is a correctness gap, not a cosmetic one**: without logs,
      a failed MicroVM compile is genuinely undiagnosable going forward — this fix is required
      before Step 8 can be called complete, not an optional nice-to-have.

      **Fourteenth bug**: with logging now working, the very next real run exposed the actual
      failure the missing logs had been hiding: a single CloudWatch Logs event for the `ARM64`
      cell — `"Invalid run hook payload: SCALEOUT_BUILD_ID must be set"` — immediately after the
      MicroVM launched, with the MicroVM then idling its full lifetime and self-terminating with
      `stateReason: "Success."` (the platform's own verdict is about the MicroVM's own lifecycle,
      not this application-level failure it has no visibility into). Root cause, confirmed against
      AWS's own documentation (Lambda MicroVMs' "Running and using MicroVMs" and the
      lifecycle-hooks reference): the `/run` hook's HTTP request body is **not** the raw
      `runHookPayload` string this launcher sends — it is an envelope the platform wraps it in,
      `{"microvmId": "...", "runHookPayload": "<the string>"}`, with `runHookPayload`'s *value*
      being the actual flat `SCALEOUT_BUILD_*` JSON object. `AgentHookServer.run()` had always
      read `exchange.getRequestBody()` directly and handed those raw bytes straight to
      `AgentConfig.fromHookPayload`, so every real `/run` call parsed the two-key envelope itself
      (`microvmId`, `runHookPayload`) as if it were the flat map — finding neither key a
      recognized `SCALEOUT_BUILD_*` name and failing on the first required one it checked. This
      had been invisible for thirteen bugs' worth of iterations purely because bug 13 (above) hid
      it: with no logging configured at all, this exact error was being thrown and discarded on
      every single run since the first successful `RunMicrovm` launch, with nothing anywhere to
      show it. Fixed by adding `AgentHookServer.unwrapRunHookEnvelope(byte[])`, which parses the
      envelope first and extracts `runHookPayload`'s string value before handing it to the
      existing `AgentConfig.fromHookPayload` unchanged — a double-JSON-decode, matching the
      platform's own documented shape exactly. `AgentHookServerTest`'s existing `hookPayload()`
      test helper was also wrong in the same way — it posted the flat map directly as the `/run`
      body, so it would have passed even with this bug present; it now wraps that same flat map in
      the real envelope before posting, and a new test
      (`runStillRespondsWithTwoHundred_whenEnvelopeIsMissingRunHookPayload`) proves a malformed
      envelope still gets its 200 rather than the hook response itself failing. **Unlike bugs 1–12,
      which were all control-plane/CDK-side (IAM, Dockerfile, launcher config) and fixable without
      touching the agent image, this one lives inside `scaleout-build-agent` itself — the next
      redeploy must rebuild and republish the MicroVM image (not `--skip-agent`), or the fix never
      actually reaches the running MicroVM regardless of what the control plane sends.**

      **Step 8 result, confirmed by direct AWS inspection, independent of the Maven client**: with
      all fourteen bugs above fixed and a freshly rebuilt MicroVM image deployed (version `1.0`,
      artifact `agent-microvm-1.0.1-v4.zip`), a real end-to-end `mvn package` against
      `docs/examples/scaleout-build-example-app` produced a `RunMicrovm`-launched `ARM64` cell
      (`microvm-4680657a-3f0a-3f68-a918-10a676c5f528`) that:
      - Received, unwrapped, and parsed its `runHookPayload` correctly (no more "SCALEOUT_BUILD_ID
        must be set").
      - Ran the actual `native-image` compile and completed it in **63 seconds**
        (`startedAt: 19:43:02`, `status.json`'s `completedAt: 19:44:05`).
      - Wrote `status.json` with `"state":"SUCCEEDED"`, `"exitCode":0`, and a correct `artifacts`
        entry (`hello-native`, SHA-256 digest, size) to the exact S3 key
        `MicroVmCellLauncher.poll()` reads from — the control plane's poll logic would have
        correctly reported this cell as succeeded.
      - Uploaded a genuine `output/hello-native` to the staging bucket, downloaded and verified
        directly: `file` reports `ELF 64-bit LSB executable, ARM aarch64, version 1 (SYSV),
        dynamically linked, ... for GNU/Linux 3.7.0, stripped` — a real, working ARM64 native
        binary, produced by GraalVM `native-image` running inside a Lambda MicroVM, not a stub or
        a copy of the x86_64 artifact.

      This proves the MicroVM backend itself — `MicroVmCellLauncher`, `MicroVmImageManager`,
      `AgentHookServer`, the CDK IAM/image plumbing — is correct end to end. Phase 1's actual goal
      (offload an `ARM64` cell to a Lambda MicroVM and get back a real binary) is achieved.

      **A fifteenth, separate issue surfaced in the same run, outside the MicroVM backend's own
      code**: the Maven plugin's own CLI still reported `BUILD FAILURE` -- `"Failed to stage
      remote build inputs: log stream failed with HTTP 403"` -- after exactly 15 minutes
      (`ControlPlaneClient.streamLogs`'s own `Duration.ofMinutes(16)` request timeout, suggesting
      a boundary close to it rather than a mid-stream drop). This is in
      `ControlPlaneClient.streamLogs`/`SigV4Signer`, shared by both backends, not something this
      session's MicroVM changes touched -- it simply never surfaced before because every prior
      Fargate build in this project's history finished in under a minute, far short of whatever
      time-based limit is actually being hit here (candidates not yet distinguished: the signing
      credentials' own session token expiring independently of SigV4's per-request timestamp, or
      a server-side limit on how long the Lambda Function URL will hold a streaming response open).
      **Not yet fixed or root-caused** -- flagged as a candidate bug 15, deliberately left for a
      separate decision rather than silently expanding this session's scope beyond Phase 1 Step 8,
      which this result above already satisfies on its own terms (the MicroVM backend works; a
      long-poll client-side log-streaming issue is a different subsystem).

      **Follow-up, made configurable at the user's request**: the real end-to-end build above ran
      with no explicit `minimumMemoryInMiB` set at all -- `MicroVmImageManager.triggerBuild()`
      never set `CreateMicrovmImageRequest.resources`, which is optional (0–1 entries). Confirmed
      against AWS's own documentation (Lambda MicroVMs' "MicroVM images" page, "MicroVM sizing"
      section, and the pricing page) that MicroVM compute follows a baseline-peak model: you set a
      memory baseline, vCPU is derived at a fixed 2:1 ratio, and the platform automatically bursts
      up to <b>4x</b> the baseline under load, billed per second only for what is actually
      consumed above baseline. The five documented discrete tiers are 512/1024/2048/4096/8192 MiB
      baseline (0.25/0.5/1/2/4 vCPU), peaking at 2/4/8/16/32 GB (1/2/4/8/16 vCPU) respectively,
      with max disk 8/8/8/16/32 GB. The platform's own undocumented default when the field is
      omitted is 2048 MiB -- confirmed by inspecting a real built image, since every image built
      so far in this phase (including the one that produced the verified ARM64 binary above)
      received this implicitly.

      Made configurable end to end, following the same pattern as `buildRoleArn`/
      `vpcEgressConnectorArn` (CDK context key → Lambda environment variable → Quarkus
      `@ConfigMapping` → `AdminResource` → `MicroVmImageManager`), as a build-time, deployment-wide
      setting rather than a per-build/per-cell one -- confirmed via `javap` that
      `resources(Collection)` exists only on `CreateMicrovmImageRequest.Builder`, never
      `RunMicrovmRequest.Builder`, and the skill's own "Image is single-size" constraint already
      established this: every MicroVM run from one image version shares the same baseline.
      - `MicroVmImageManager.SUPPORTED_MINIMUM_MEMORY_MIB` (the five discrete tiers) and
        `DEFAULT_MINIMUM_MEMORY_MIB` (2048, matching the platform's own prior implicit default so
        existing deployments are not silently made more expensive by this change).
        `triggerBuild()` gained a required `minimumMemoryInMiB` parameter, validated against the
        tier list with `IllegalArgumentException` naming the invalid value and the valid set
        (rather than reaching the platform and getting back an opaque `ValidationException`), and
        sets `.resources(Resources.builder().minimumMemoryInMiB(...).build())` on the request.
      - `ControlPlaneConfig.MicroVm.minimumMemoryMib()` (new `@WithDefault("2048")` field; named to
        avoid the Quarkus kebab-case ambiguity a literal `minimumMemoryInMiB` name would create
        around the "InMiB" acronym boundary).
      - `AdminResource.triggerImageBuild()` passes `config.microVm().minimumMemoryMib()` through,
        and translates `IllegalArgumentException` from the manager into a clear
        `InvalidConfiguration` 500 rather than letting it surface as an unhandled exception.
      - CDK: new `microvmMinimumMemoryMib` context key (default `"2048"`), read alongside
        `microvmAdminRoleArn` (image building is gated by the admin role, not by
        `microvmEnabled`, which only gates whether built images are used for build-execution
        cells) and emitted as `SCALEOUT_MICROVM_MINIMUM_MEMORY_MIB` on the service function.
        Verified by direct `cdk synth` with `-c microvmMinimumMemoryMib=4096`: the Lambda's
        environment correctly carries `SCALEOUT_MICROVM_MINIMUM_MEMORY_MIB: "4096"`.
      - Tests: `MicroVmImageManagerTest` gained `resources` assertions on the happy path plus two
        new tests (`triggerBuild_rejects_a_minimumMemoryInMiB_outside_the_documented_tiers`,
        `triggerBuild_accepts_every_documented_tier`); `AdminResourceTest`'s mock updated for the
        new parameter. Full reactor `mvn -B clean verify` passes; `scripts/verify-synth.sh` (which
        never sets MicroVM context at all) still passes unaffected, confirming the new field's
        default does not change behavior for Fargate-only deployments.

      **Not yet done**: redeploying the live stack with an explicit, higher tier and re-running
      the real end-to-end build to confirm a non-default `minimumMemoryInMiB` actually changes
      the built image's observed resource ceiling (would need either `/proc/meminfo`/`nproc`
      logged from inside the agent, or `SHELL_INGRESS` access to a live MicroVM, since Phase 1's
      agent does not currently log this). The configuration plumbing is proven correct by direct
      `cdk synth` inspection and the full test suite; the actual AWS-side effect of raising the
      tier was not re-verified against a real deploy in this session.

      **Root cause found and fixed for the "15-minute client failure"**: diagnosed by reading the
      control plane's own CloudWatch Logs (`/aws/lambda/scaleout-build-control-plane`) for the
      exact invocation handling the earlier failed stream, rather than continuing to guess at a
      stale-credential theory. Two independent, pre-existing bugs, neither introduced by this
      phase's own MicroVM work and both invisible until a build genuinely took long enough to
      exercise them:

      1. **A native-image reflection gap**, confirmed by direct evidence: a real build's
         `status.json` was fetched straight from S3 and confirmed valid, well-formed JSON
         (`{"buildId":...,"state":"SUCCEEDED",...}`), yet the control plane's own logs showed
         `MicroVmCellLauncher` logging `"Malformed status.json ... Cannot construct instance"` on
         every single poll, every ~3 seconds, for the cell's entire ~12-minute visible lifetime —
         never once succeeding against a file that was never actually malformed. The control
         plane runs as a GraalVM native image (`scaleout-build-control-plane 1.0.1 native`), and
         `MicroVmCellLauncher.StatusReport` — read via a plain `new ObjectMapper().readValue(...)`
         call with no JAX-RS method signature for Quarkus to infer a type from — was never
         registered for reflection anywhere, the same failure category
         `WireContractReflectionConfig`'s own javadoc already documents for wire DTOs, but for a
         type that was never a wire DTO at all and therefore outside that config's own scope. The
         practical effect: **the control plane never once learned a MicroVM cell actually
         succeeded**, for any build, the whole time this phase has been testing against real AWS
         — `poll()` silently fell through to the `GetMicrovm` "still running" fallback on every
         single call. Fixed by widening `MicroVmCellLauncher.StatusReport` from `private` to
         `public` (required just to let a different module/package *name* the type at all — a
         private nested record cannot be referenced from another compilation unit regardless of
         any reflection concern) and adding a new `MicrovmReflectionConfig` class in the
         control-plane module (parallel to, but deliberately not merged into,
         `WireContractReflectionConfig`, since this is a structurally different category of
         reflection gap). A new `WireContractReflectionTest.microVmStatusReportIsRegisteredForReflection`
         pins the registration so removing it fails the suite rather than silently reintroducing
         this exact production failure.
      2. **A blocking AWS SDK call on the Vert.x event loop**, also confirmed directly: the same
         logs showed repeated `BlockedThreadChecker` warnings — `vert.x-eventloop-thread-0` blocked
         for 3.6s, 5.6s, 7.6s, then 9.6s, each time inside
         `CloudWatchLogsClient.filterLogEvents` called from `LogStreamResource.drain()` →
         `poll()` → the SSE stream's own `flatMap`. `@Blocking` on `streamLogs()` only dispatches
         the *initial* method invocation to a worker thread; it does not apply to work scheduled
         later via `Multi.createFrom().ticks()`, which runs on Vert.x's own timer/event-loop
         context by default. This blocked the very tick that was supposed to notice
         `Instant.now().isAfter(deadline)` at `STREAM_BUDGET` (780s) and hand off gracefully with
         `StreamEndReason.LAMBDA_TIMEOUT` — so that graceful 13-minute cutoff never fired, and the
         invocation ran all the way to the Lambda's own hard ceiling. Confirmed by the exact
         CloudWatch Logs REPORT line for that request: `Duration: 900000.00 ms  Billed Duration:
         900000 ms  Status: timeout` — precisely 900 seconds, the Lambda invocation limit, not a
         deliberate exit. The Lambda runtime killing the function mid-response is what severed the
         client's connection, which is what the Maven plugin's `ControlPlaneClient` ultimately
         surfaced as a build failure (the originally reported "log stream failed with HTTP 403" is
         consistent with a *separate* reconnect attempt genuinely receiving a 403 from a fresh
         connection — not with the long-held connection's own abrupt termination, which would
         raise an `IOException`, not a clean HTTP response — so there may still be a third,
         distinct signing issue on reconnect specifically, not yet investigated further since the
         two bugs above were sufficient to fix the reported symptom end to end). Fixed by inserting
         `.emitOn(Infrastructure.getDefaultWorkerPool())` between the tick source and the
         `flatMap` that calls `poll()`, moving each tick's blocking CloudWatch call off the event
         loop and keeping the scheduling timer itself free to fire on time.

      **Verified end to end against real AWS after both fixes**, same `scaleout-build-example-app`:
      `mvn -B clean package` completed in **1 minute 45 seconds** with `BUILD SUCCESS` —
      `[NATIVE/ARM64] -> SUCCEEDED` reported correctly this time, both `hello-native` binaries
      (confirmed genuine ARM64 and x86_64 ELF executables via `file`) attached to the Maven
      reactor with their classifiers. This is the first run in this entire phase where the client
      itself reported success for the MicroVM cell, rather than a human having to independently
      verify success via direct AWS API/S3 inspection after an apparent client-side failure.

## Phase 3 (not started — do not begin before Phase 1 is done and deployed)

Read `microvm-warm-pool-phase3.md` in full when this becomes the active work. Five open questions
are listed at the end of that document and are **not optional reading** — in particular, whether
auth-token expiry kills an in-flight SSE stream was researched and explicitly not resolved either
way; that needs a real test against the platform before any code depending on long-lived streaming
connections is written.

## Standing reminders for whoever picks this up

- **Verify SDK behavior via `javap` against the actual jar, don't trust memory or the design
  docs' own prose as ground truth once new code is being written.** Several details in both
  Phase 1 and Phase 3's design docs (e.g. `RunMicrovmResponse` already returning `endpoint()`
  directly, `MicrovmState` having no `FAILED` value, default port being 8080 not 8443) were found
  by directly inspecting `software.amazon.awssdk:lambdamicrovms` and reading AWS's current docs
  during this session, not by recalling them — the project's own `.kiro/steering/tech.md`
  "verify, don't remember" rule applies here as much as it does to dependency versions.
- **`mvn clean verify` must stay green after every step.** Run it before moving to the next step,
  not just at the end — this codebase's own `HANDOVER.md` records multiple incidents where a
  defect only surfaced at actual deploy time because nothing exercised the real path earlier.
- **Don't skip the "pure extraction" verification mindset from step 1 for later steps.** Where a
  step is extracting existing logic rather than writing new logic, write the test that proves the
  extraction didn't change behavior *before* trusting it, the same way `FargateCellLauncherTest`
  asserts on captured request shapes rather than just "it compiles and doesn't throw."
