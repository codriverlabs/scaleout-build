/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.controlplane;

import ai.codriverlabs.scaleoutbuild.ecs.MicroVmCellLauncher;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Registers {@code scaleout-build-ecs} types for reflection in the control plane's native image,
 * for the same reason {@link WireContractReflectionConfig} exists for the API module's wire DTOs
 * — but these are a genuinely different category, which is why they get their own class rather
 * than being folded into that one.
 *
 * <h2>Why this is not a wire DTO problem</h2>
 *
 * {@link WireContractReflectionConfig}'s own javadoc explains its failure mode: a JAX-RS method
 * returning a raw {@code jakarta.ws.rs.core.Response} gives Quarkus nothing to infer an entity
 * type from, so types never returned through a typed REST method signature need registering by
 * hand. {@link MicroVmCellLauncher.StatusReport} is never a wire DTO at all — it is never sent or
 * received over this service's own REST surface. It is read with a plain, unmanaged
 * {@code new ObjectMapper().readValue(bytes, StatusReport.class)} call inside
 * {@code MicroVmCellLauncher.readStatusReport}, deserializing {@code status.json} as written by
 * {@code AgentHookServer} (a different module, {@code scaleout-build-agent}) to S3. Quarkus's
 * Jackson extension has no REST method signature to infer this type from either — there simply is
 * no automatic path by which this registration could happen, regardless of how the wire DTOs are
 * handled.
 *
 * <h2>Found the hard way, in production, against a real MicroVM build</h2>
 *
 * A real end-to-end build's {@code status.json} was confirmed valid, well-formed JSON by reading
 * it directly from S3 ({@code {"buildId":...,"state":"SUCCEEDED",...}}) — yet
 * {@code MicroVmCellLauncher.poll()} logged {@code "Malformed status.json ... Cannot construct
 * instance"} on every single poll attempt for the build's entire run, in the control plane's own
 * deployed native image. JVM mode never exercises this path at all (reflection is unrestricted
 * there), so nothing before an actual native deployment against a real MicroVM build could have
 * caught it — the same blind spot {@code WireContractReflectionConfig}'s own javadoc already
 * describes for its own category of type ("JVM mode never needed any of this, which is why it
 * stayed invisible until the first native deployment"). The practical effect: the control plane
 * never saw this cell's real {@code SUCCEEDED} outcome at all, falling through to
 * {@code GetMicrovm}'s "still running" fallback for the cell's entire lifetime.
 *
 * <p>{@link MicroVmCellLauncher.StatusReport} had to be widened from {@code private} to
 * {@code public} to make this possible at all — see that record's own javadoc for why a
 * {@code private} nested record cannot be named from a different module's compilation unit in
 * the first place, independent of any runtime reflection concern.
 */
@RegisterForReflection(targets = {
    MicroVmCellLauncher.StatusReport.class,
})
public final class MicrovmReflectionConfig {

    private MicrovmReflectionConfig() {
    }
}
