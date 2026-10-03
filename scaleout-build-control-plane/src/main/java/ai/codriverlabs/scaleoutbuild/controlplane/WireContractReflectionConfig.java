/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.controlplane;

import ai.codriverlabs.scaleoutbuild.controlplane.api.AdminApi;
import ai.codriverlabs.scaleoutbuild.controlplane.api.ArtifactDescriptor;
import ai.codriverlabs.scaleoutbuild.controlplane.api.ArtifactDownload;
import ai.codriverlabs.scaleoutbuild.controlplane.api.ArtifactListResponse;
import ai.codriverlabs.scaleoutbuild.controlplane.api.BuildSpec;
import ai.codriverlabs.scaleoutbuild.controlplane.api.BuildState;
import ai.codriverlabs.scaleoutbuild.controlplane.api.BuildStatus;
import ai.codriverlabs.scaleoutbuild.controlplane.api.CellState;
import ai.codriverlabs.scaleoutbuild.controlplane.api.CellStatus;
import ai.codriverlabs.scaleoutbuild.controlplane.api.CreateBuildRequest;
import ai.codriverlabs.scaleoutbuild.controlplane.api.CreateBuildResponse;
import ai.codriverlabs.scaleoutbuild.controlplane.api.ErrorResponse;
import ai.codriverlabs.scaleoutbuild.controlplane.api.InputDescriptor;
import ai.codriverlabs.scaleoutbuild.controlplane.api.LogEvent;
import ai.codriverlabs.scaleoutbuild.controlplane.api.RequestedResources;
import ai.codriverlabs.scaleoutbuild.controlplane.api.StreamEndReason;
import ai.codriverlabs.scaleoutbuild.controlplane.api.UploadTarget;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Registers the wire DTOs for reflection in the native image.
 *
 * <p>Needed because {@link ai.codriverlabs.scaleoutbuild.controlplane.api.BuildApi} methods return
 * {@code jakarta.ws.rs.core.Response} rather than a concrete type. Quarkus auto-registers types it can
 * infer from a REST method signature; with a raw {@code Response} there is nothing to infer, so in a
 * native image Jackson finds no serializer and every response body fails:
 *
 * <pre>
 *   Jackson was unable to serialize type 'ErrorResponse' ... No serializer found ... This appears to be
 *   a native image, in which case you may need to configure reflection for the class
 * </pre>
 *
 * <p>Adding {@code quarkus.index-dependency} for the API module is necessary but <em>not sufficient</em>:
 * indexing makes the classes discoverable, while this annotation is what actually causes reflection
 * metadata to be emitted for them.
 *
 * <p>Declared here, in the service module, using {@code targets} rather than annotating each record
 * where it lives. The API module is also consumed by the Maven plugin, which must not acquire a
 * dependency on Quarkus runtime annotations just so the service can be compiled to a native binary.
 * Reflection configuration is the native image's concern, so it belongs to the module that builds one.
 *
 * <p>JVM mode never needed any of this, which is why it stayed invisible until the first native
 * deployment. Health checks passed throughout — Quarkus serves those itself — while every {@code /builds}
 * response returned HTTP 500.
 *
 * <p>{@code WireContractReflectionTest} asserts this list stays complete as the API changes, since a
 * missing entry is otherwise a runtime 500 in native mode only.
 */
@RegisterForReflection(targets = {
    AdminApi.class,
    AdminApi.TriggerImageBuildRequest.class,
    AdminApi.ImageBuildResponse.class,
    ArtifactDescriptor.class,
    ArtifactDownload.class,
    ArtifactListResponse.class,
    ArtifactListResponse.CellArtifacts.class,
    BuildSpec.class,
    BuildState.class,
    BuildStatus.class,
    CellState.class,
    CellStatus.class,
    CreateBuildRequest.class,
    CreateBuildResponse.class,
    ErrorResponse.class,
    InputDescriptor.class,
    LogEvent.class,
    LogEvent.Type.class,
    RequestedResources.class,
    StreamEndReason.class,
    UploadTarget.class,
})
public final class WireContractReflectionConfig {

    private WireContractReflectionConfig() {
    }
}
