/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.controlplane.api;

import java.util.List;

/**
 * Body of {@link BuildApi#createBuild}.
 *
 * @param buildSpec          what to build
 * @param inputs             the complete input manifest, digest-identified
 * @param requestedResources advisory sizing, may be {@code null} for server defaults
 * @param clientVersion      free-form client identifier, e.g.
 *                           {@code scaleout-build-maven-plugin/1.1.0}, recorded for support and for
 *                           spotting clients that predate a contract change
 */
public record CreateBuildRequest(BuildSpec buildSpec, List<InputDescriptor> inputs,
                                 RequestedResources requestedResources, String clientVersion) {

    public CreateBuildRequest {
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
    }
}
