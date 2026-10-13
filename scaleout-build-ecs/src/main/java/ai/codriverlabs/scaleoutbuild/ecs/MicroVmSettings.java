/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.ecs;

import java.util.Objects;

/**
 * The build-execution MicroVM settings {@link MicroVmCellLauncher} needs, independent of
 * {@link EcsClusterSettings} (which {@code FargateCellLauncher} alone reads — see
 * {@code CellLaunchRequest}'s own javadoc).
 *
 * <p>{@code executionRoleArn} is deliberately the same role the admin image-build flow already
 * uses for its own runtime permissions (S3 staging bucket read/write, CloudWatch Logs write) — see
 * {@code docs/design/control-plane/microvm-build-backend-phase1.md}'s "Config additions" section
 * for why provisioning a second role for an identical permission set would be duplication with no
 * isolation benefit.
 *
 * <p><b>{@code vpcEgressConnectorArn} is optional</b> — confirmed against AWS's own documentation
 * (Lambda MicroVMs' "Networking" page) that a MicroVM has public internet access by default, and
 * an egress connector only exists to route outbound traffic through a customer-managed VPC
 * *instead*, for reaching private resources (databases, caches, internal APIs). Phase 1's agent
 * needs only S3 (staging bucket) and CloudWatch Logs, both public AWS service endpoints reachable
 * over the default public path via the execution role's IAM permissions alone — there is no
 * private resource to reach, so no connector is required. Also confirmed via {@code javap}
 * against the real SDK: {@code RunMicrovmRequest.egressNetworkConnectors()} is backed by an
 * {@code SdkAutoConstructList} (the SDK's standard "defaults to empty, not required" shape), not a
 * required field. This was wrongly treated as a hard requirement earlier in this phase's own
 * design doc and task list — corrected here once actually checked, the same "verify, don't
 * remember" principle this project's own {@code .kiro/steering/tech.md} already states for
 * dependency versions, applying equally to platform capability claims.
 *
 * @param imageArn               ARN of the MicroVM image to launch (the published
 *                               {@code scaleout-build-agent} image, see {@code MicroVmImageManager})
 * @param imageVersion           version of that image to launch
 * @param executionRoleArn       role the MicroVM assumes at runtime; reused from the admin flow
 * @param vpcEgressConnectorArn  VPC egress connector ARN, or {@code null}/blank to use the
 *                               MicroVM's default public internet access (sufficient for S3 and
 *                               CloudWatch Logs, Phase 1's only two destinations)
 * @param logGroupName           CloudWatch Logs group the MicroVM's stdout/stderr is sent to.
 *                               Reused unchanged from {@code ControlPlaneConfig.Ecs.logGroupName()}
 *                               — it is the same {@code agentLogGroup} the CDK stack already grants
 *                               write access to {@code microvmExecutionRole}, so there is no second
 *                               group to provision. Without this, {@code RunMicrovmRequest} omits
 *                               {@code logging} entirely and the agent's output is unobservable:
 *                               found by actually running a build end to end and seeing zero log
 *                               events for the MicroVM cell while the Fargate cell's logs were
 *                               complete.
 */
public record MicroVmSettings(
        String imageArn,
        String imageVersion,
        String executionRoleArn,
        String vpcEgressConnectorArn,
        String logGroupName) {

    public MicroVmSettings {
        requireNonBlank(imageArn, "imageArn");
        requireNonBlank(imageVersion, "imageVersion");
        requireNonBlank(executionRoleArn, "executionRoleArn");
        requireNonBlank(logGroupName, "logGroupName");
        // vpcEgressConnectorArn: no validation -- null/blank both mean "use the default public
        // path", which MicroVmCellLauncher.launch() must check for explicitly rather than passing
        // an empty/blank string through to RunMicrovmRequest.egressNetworkConnectors().
    }

    /** @return whether an egress connector was configured, as opposed to the MicroVM's default
     * public internet access */
    public boolean hasVpcEgressConnector() {
        return vpcEgressConnectorArn != null && !vpcEgressConnectorArn.isBlank();
    }

    private static void requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
