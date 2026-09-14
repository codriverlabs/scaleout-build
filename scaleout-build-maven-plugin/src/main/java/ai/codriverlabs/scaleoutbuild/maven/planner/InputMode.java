/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.maven.planner;

/** How a project's native-image inputs were obtained. */
public enum InputMode {

    /**
     * Quarkus emitted a {@code native-image.args} file next to a runner jar and its {@code lib/}
     * directory. The argfile is passed through verbatim, because extensions inject many {@code -H:}
     * flags that cannot be reconstructed safely.
     */
    QUARKUS_NATIVE_SOURCES,

    /**
     * No generated argfile was available, so one is derived from the project's runtime classpath and
     * plugin configuration.
     */
    DERIVED
}
