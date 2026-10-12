/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.ecs;

import ai.codriverlabs.scaleoutbuild.build.Architecture;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.lambdamicrovms.LambdaMicrovmsClient;
import software.amazon.awssdk.services.lambdamicrovms.model.CloudWatchLogging;
import software.amazon.awssdk.services.lambdamicrovms.model.GetMicrovmRequest;
import software.amazon.awssdk.services.lambdamicrovms.model.GetMicrovmResponse;
import software.amazon.awssdk.services.lambdamicrovms.model.IdlePolicy;
import software.amazon.awssdk.services.lambdamicrovms.model.Logging;
import software.amazon.awssdk.services.lambdamicrovms.model.MicrovmState;
import software.amazon.awssdk.services.lambdamicrovms.model.RunMicrovmRequest;
import software.amazon.awssdk.services.lambdamicrovms.model.RunMicrovmResponse;
import software.amazon.awssdk.services.lambdamicrovms.model.TerminateMicrovmRequest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * {@link CellLauncher} for Lambda MicroVMs — {@code ARM64} only, additive to
 * {@link FargateCellLauncher} rather than a replacement, per
 * {@code docs/design/control-plane/microvm-build-backend-phase1.md}. Phase 1: no suspend/resume,
 * one build per MicroVM, no ingress connector (the control plane never calls into the MicroVM's
 * endpoint — see "Why no ingress connector" below).
 *
 * <h2>Divergence from the design doc, found by {@code javap} against the actual
 * {@code software.amazon.awssdk:lambdamicrovms} jar</h2>
 *
 * The design doc's own sketch builds {@code runHookPayload} via
 * {@code SdkBytes.fromUtf8String(...)}. Confirmed via {@code javap} against the real
 * {@code RunMicrovmRequest$Builder} that {@code runHookPayload} takes a plain {@code String}, not
 * {@code SdkBytes} — there is no {@code SdkBytes}-accepting overload at all. This class passes the
 * JSON string directly. Everything else in the doc's sketch ({@code idlePolicy},
 * {@code maximumDurationInSeconds}, {@code egressNetworkConnectors}, {@code RunMicrovmResponse}
 * exposing {@code microvmId()}/{@code endpoint()} directly, {@code MicrovmState} having no
 * {@code FAILED} value) was confirmed to match exactly.
 *
 * <h2>Why no ingress connector, and what that implies for status polling</h2>
 *
 * The control plane never calls into a MicroVM's endpoint — the agent reports completion via a side
 * channel ({@code status.json} in S3, written by {@link AgentHookServer} — not yet in this module,
 * see {@code scaleout-build-agent}) instead, with this launcher as a passive reader. That is why
 * {@link #launch} sets no ingress connector and {@link #poll} never calls the endpoint
 * {@code RunMicrovmResponse}/{@code GetMicrovmResponse} report.
 */
public final class MicroVmCellLauncher implements CellLauncher {

    private static final Logger LOG = LoggerFactory.getLogger(MicroVmCellLauncher.class);

    /**
     * Headroom between the compile budget and the platform's own {@code maximumDurationInSeconds}
     * backstop, mirroring {@code AgentEnvironment.CONTAINER_TIMEOUT_MARGIN_MINUTES}'s reasoning for
     * the Fargate path's container-wide timeout: generous rather than tight, since this bound exists
     * to stop a stalled MicroVM, and firing it early on a healthy one would terminate a completing
     * compile before it could write {@code status.json}.
     */
    static final int MAX_DURATION_MARGIN_MINUTES = 10;

    private final LambdaMicrovmsClient microvms;
    private final S3Client s3Client;
    private final MicroVmSettings settings;
    private final String stagingBucket;
    private final ObjectMapper mapper = new ObjectMapper();

    public MicroVmCellLauncher(LambdaMicrovmsClient microvms, S3Client s3Client,
                               MicroVmSettings settings, String stagingBucket) {
        this.microvms = Objects.requireNonNull(microvms, "microvms");
        this.s3Client = Objects.requireNonNull(s3Client, "s3Client");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.stagingBucket = requireNonBlank(stagingBucket, "stagingBucket");
    }

    /*
     * maxIdleDurationSeconds/maximumDurationInSeconds in launch() below are set to the same
     * value, confirmed required by actually launching a real MicroVM and watching it die: a
     * first version of this launcher set a short, fixed idle duration (60s), reasoning "terminate
     * promptly once the /run hook returns -- Phase 1 never suspends." That reasoning was wrong.
     * AWS's own documentation on IdlePolicy states plainly: "Idle time is measured by inbound
     * traffic through the MicroVM proxy endpoint." Once /run returns 200, the compile runs on a
     * background thread with zero further HTTP traffic to the endpoint -- exactly the condition
     * the platform calls idle -- so a short idle budget does not wait for the compile to finish;
     * it starts counting the moment the real work begins. Confirmed end to end: a real
     * RunMicrovm launch with the 60s value succeeded, then GetMicrovm showed TERMINATED after
     * exactly 64 seconds with stateReason: "MicroVM exceeded maximum lifetime.", even though
     * maximumDurationInSeconds was 2400 -- the idle policy, not the max-lifetime one, killed it,
     * and autoResumeEnabled(false) means a suspend here is a termination with extra steps.
     */

    @Override
    public CellHandle launch(CellLaunchRequest request) throws CellLaunchException {
        if (request.architecture() != Architecture.ARM64) {
            // Enforced here rather than left to the platform's own validation, since a caller
            // routing X86_64 to this launcher is this module's own dispatch bug, not a user error
            // -- see BuildService.cellLauncher(Architecture)'s own contract once step 5 adds it.
            throw new CellLaunchException(
                    "MicroVmCellLauncher only accepts ARM64 cells, got " + request.architecture());
        }
        RunMicrovmResponse response;
        try {
            String payload = runHookPayloadJson(request);
            int maxDurationSeconds = maxDurationSecondsFor(request);
            RunMicrovmRequest.Builder requestBuilder = RunMicrovmRequest.builder()
                    .imageIdentifier(settings.imageArn())
                    .imageVersion(settings.imageVersion())
                    .executionRoleArn(settings.executionRoleArn())
                    .idlePolicy(IdlePolicy.builder()
                            // Must cover the whole compile, not just the time until /run
                            // returns -- see this field's own javadoc below for why a short
                            // value (this class's first version) actively kills a healthy
                            // build. Matching maxDurationSeconds means idle and max-lifetime
                            // never race each other: whichever fires first does so for a reason
                            // (the compile is either done, or it is hung), not because of this
                            // policy's own internal inconsistency.
                            .maxIdleDurationSeconds(maxDurationSeconds)
                            .suspendedDurationSeconds(0)
                            .autoResumeEnabled(false)
                            .build())
                    .maximumDurationInSeconds(maxDurationSeconds)
                    .runHookPayload(payload)
                    // Explicit, rather than relying on the platform's own default log group
                    // (/aws/lambda-microvms/<image-name>): confirmed via AWS's own
                    // documentation (Lambda MicroVMs' "Monitoring" page) that runtime logs are
                    // only forwarded at all when the execution role holds
                    // logs:CreateLogGroup/CreateLogStream/PutLogEvents, and routing to that
                    // default group would need its own IAM grant for a group this stack never
                    // provisions. Pointing at the same agentLogGroup the Fargate path already
                    // uses means one place to look for either backend's output, and the
                    // CreateLogStream/PutLogEvents grant microvmExecutionRole already has
                    // (ControlPlaneInfraStack's agentLogGroup.grantWrite) is sufficient since
                    // the group itself already exists -- CreateLogGroup is only invoked for a
                    // group that is not already there. The log stream defaults to the cell id
                    // so each cell's output is distinguishable within the shared group, the
                    // same role request.cellId() already plays for the Fargate path's own
                    // awslogs-stream-prefix.
                    .logging(Logging.builder()
                            .cloudWatch(CloudWatchLogging.builder()
                                    .logGroup(settings.logGroupName())
                                    .logStream(request.cellId())
                                    .build())
                            .build());
                    // No ingress connector: see this class's own javadoc for why the control
                    // plane never calls into the MicroVM.
            if (settings.hasVpcEgressConnector()) {
                requestBuilder.egressNetworkConnectors(List.of(settings.vpcEgressConnectorArn()));
            }
            // Else: no egress connector configured, so the MicroVM uses its default public
            // internet access -- sufficient for S3 and CloudWatch Logs, Phase 1's only two
            // destinations. See MicroVmSettings' own javadoc for why this is not a hard
            // requirement, confirmed against AWS's documentation and the SDK itself.
            response = microvms.runMicrovm(requestBuilder.build());
        } catch (SdkException e) {
            throw new CellLaunchException("RunMicrovm failed: " + e.getMessage(), e);
        }
        LOG.info("Launched MicroVM {} for cell {} (endpoint {})", response.microvmId(),
                request.cellId(), response.endpoint());
        return new CellHandle.MicroVmHandle(response.microvmId(), response.endpoint(),
                request.stagingRelativePath());
    }

    @Override
    public CellPollResult poll(CellHandle handle) {
        CellHandle.MicroVmHandle h = (CellHandle.MicroVmHandle) handle;
        Optional<StatusReport> report = readStatusReport(h);
        if (report.isPresent()) {
            StatusReport r = report.get();
            if ("SUCCEEDED".equals(r.state())) {
                return CellPollResult.succeeded(r.exitCode());
            }
            return CellPollResult.failed(r.exitCode(), "MicroVM build reported failure (exitCode="
                    + r.exitCode() + ")");
        }
        // No status.json yet. Fall back to GetMicrovm purely to distinguish "still running" from
        // "the platform itself killed it" (idle or maximum duration limit) -- the one thing
        // status.json cannot report, because the agent never got to write it. See this class's
        // own javadoc and the design doc's "Poll" section for why this fallback exists at all.
        GetMicrovmResponse response;
        try {
            response = microvms.getMicrovm(
                    GetMicrovmRequest.builder().microvmIdentifier(h.microvmId()).build());
        } catch (SdkException e) {
            LOG.warn("GetMicrovm failed for {}: {}", h.microvmId(), e.getMessage());
            return CellPollResult.running();
        }
        MicrovmState state = response.state();
        if (state == MicrovmState.PENDING || state == MicrovmState.RUNNING) {
            return CellPollResult.running();
        }
        if (state == MicrovmState.TERMINATED || state == MicrovmState.TERMINATING) {
            return CellPollResult.failed(null, "MicroVM terminated before writing a status report "
                    + "(stateReason: " + response.stateReason() + ") -- likely the idle or maximum "
                    + "duration limit, not a build failure");
        }
        if (state == MicrovmState.SUSPENDED || state == MicrovmState.SUSPENDING) {
            return CellPollResult.failed(null,
                    "MicroVM suspended unexpectedly -- Phase 1 does not use suspend/resume, so this "
                            + "indicates a platform-side state this launcher does not expect");
        }
        return CellPollResult.running();
    }

    @Override
    public void stop(CellHandle handle, String reason) {
        CellHandle.MicroVmHandle h = (CellHandle.MicroVmHandle) handle;
        try {
            microvms.terminateMicrovm(
                    TerminateMicrovmRequest.builder().microvmIdentifier(h.microvmId()).build());
        } catch (SdkException e) {
            // Already terminated, or transient -- same tolerance FargateCellLauncher.stop() already
            // has for StopTask failing on an already-stopped task.
            LOG.warn("TerminateMicrovm failed for {}: {}", h.microvmId(), e.getMessage());
        }
    }

    /**
     * Reads {@code status.json} from the staging prefix the agent wrote to, if present. The key
     * mirrors {@code AgentHookServer.writeStatusJson}'s own construction on the write side — both
     * must agree on this one path, the same cross-module string-contract risk
     * {@code AgentEnvironment}'s javadoc already documents for the {@code SCALEOUT_BUILD_*} names.
     */
    private Optional<StatusReport> readStatusReport(CellHandle.MicroVmHandle handle) {
        String key = joinKey(handle.stagingRelativePath(), "status.json");
        try {
            byte[] bytes = s3Client.getObjectAsBytes(
                    software.amazon.awssdk.services.s3.model.GetObjectRequest.builder()
                            .bucket(stagingBucket).key(key).build())
                    .asByteArray();
            return Optional.of(mapper.readValue(bytes, StatusReport.class));
        } catch (NoSuchKeyException e) {
            return Optional.empty(); // Not written yet -- still compiling, or never will be.
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            LOG.warn("Failed to read status.json for MicroVM {}: {}", handle.microvmId(),
                    e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            LOG.warn("Malformed status.json for MicroVM {}: {}", handle.microvmId(), e.getMessage());
            return Optional.empty();
        }
    }

    private String runHookPayloadJson(CellLaunchRequest request) throws CellLaunchException {
        try {
            return mapper.writeValueAsString(request.agentEnvironment());
        } catch (Exception e) {
            throw new CellLaunchException("Failed to serialize runHookPayload: " + e.getMessage(), e);
        }
    }

    /**
     * {@code (overallTimeoutMinutes + margin) * 60}, mirroring
     * {@code ControlPlaneConfig.Ecs.defaultCellTimeoutMinutes}'s own margin reasoning: this is the
     * MicroVM path's equivalent of the Fargate path's container-wide {@code coreutils timeout}
     * wrapper -- an outer backstop enforced by the platform rather than a process wrapper, set
     * generously above the inner compile-specific timeout so a healthy build's upload is never cut
     * off mid-flight.
     */
    private int maxDurationSecondsFor(CellLaunchRequest request) {
        int timeoutMinutes = timeoutMinutesFrom(request);
        return (timeoutMinutes + MAX_DURATION_MARGIN_MINUTES) * 60;
    }

    private static int timeoutMinutesFrom(CellLaunchRequest request) {
        String raw = request.agentEnvironment().get("SCALEOUT_BUILD_TIMEOUT_MINUTES");
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String joinKey(String prefix, String relativePath) {
        return prefix.endsWith("/") ? prefix + relativePath : prefix + "/" + relativePath;
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    /**
     * The {@code status.json} shape {@code AgentHookServer} writes, read back here.
     *
     * <p><b>Public, not {@code private}, for one reason only</b>: {@code
     * ControlPlaneMicrovmReflectionConfig} (in {@code scaleout-build-control-plane}, a different
     * module and package) must reference this type by name to register it for native-image
     * reflection, and a {@code private} nested record cannot be named from outside its own
     * compilation unit at all -- the compiler rejects it, it is not merely inaccessible at
     * runtime. Found the hard way: a real MicroVM build's {@code status.json} was valid,
     * well-formed JSON (confirmed by reading it directly from S3), yet {@code readStatusReport}
     * logged "Malformed status.json ... Cannot construct instance" on every single poll for the
     * entire run, in the control plane's own native image. {@code WireContractReflectionConfig}
     * already documents exactly this failure mode for wire DTOs Quarkus cannot infer from a raw
     * JAX-RS {@code Response} type; this is the same failure mode for a type that is never a wire
     * DTO at all -- it is read by a plain {@code ObjectMapper.readValue} call with no JAX-RS
     * method signature for Quarkus to infer anything from, so nothing registers it automatically
     * either way. There is still no outside caller of this record other than Jackson's own
     * reflective constructor invocation -- {@code public} widens compile-time visibility, not the
     * type's actual usage.
     */
    public record StatusReport(String buildId, String cell, String state, int exitCode,
                                List<Map<String, Object>> artifacts, String completedAt) {
    }
}
