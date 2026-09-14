/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.build;

import java.util.Objects;

/**
 * Path and S3 key layout for staged build inputs and produced artifacts.
 *
 * <p>All returned values are relative, POSIX-style paths. That is deliberate: the same string is
 * used as an S3 key by the plugin and as a mount-relative path by the agent, because an ECS S3
 * Files volume exposes objects as files under the mounted bucket.
 *
 * <p>Layout:
 * <pre>
 * cas/{sha256}                              content-addressed blobs, shared across builds, kinds and architectures
 * builds/{buildId}/{buildKind}/{arch}/      staging root for one matrix cell, and the process working directory
 *     native-image.args
 *     &lt;runner&gt;.jar
 *     lib/*.jar
 *     output/                               binary destination when the argfile directs output there
 * </pre>
 *
 * <p>The build kind is part of the path (not just the architecture) because one plugin invocation
 * can request more than one build kind for the same architecture in the same run — e.g. both
 * {@code NATIVE} and {@code NATIVE_PGO_OPTIMIZE} for {@code x86_64} — and those need distinct
 * staging roots even though they share a {@code buildId}.
 *
 * <p>Classpath jars are uploaded once into {@code cas/} and then materialised into a build's
 * {@code lib/} directory with server-side copies. That keeps the argfile free of indirection (which
 * matters when Quarkus generated it verbatim) while still uploading only changed bytes.
 */
public final class StagingLayout {

    public static final String DEFAULT_ARGS_FILE_NAME = "native-image.args";
    public static final String OUTPUT_DIR_NAME = "output";
    public static final String LIB_DIR_NAME = "lib";

    private static final String DEFAULT_BUILDS_PREFIX = "builds";
    private static final String DEFAULT_CAS_PREFIX = "cas";

    private final String buildsPrefix;
    private final String casPrefix;

    public StagingLayout(String buildsPrefix, String casPrefix) {
        this.buildsPrefix = normalizePrefix(buildsPrefix, DEFAULT_BUILDS_PREFIX);
        this.casPrefix = normalizePrefix(casPrefix, DEFAULT_CAS_PREFIX);
    }

    public static StagingLayout defaults() {
        return new StagingLayout(DEFAULT_BUILDS_PREFIX, DEFAULT_CAS_PREFIX);
    }

    /** Prefix holding everything for one build, across all architectures. */
    public String buildPrefix(String buildId) {
        return buildsPrefix + "/" + requireId(buildId);
    }

    /**
     * Staging root for one matrix cell: the directory the argfile paths are relative to, and the
     * working directory {@code native-image} is launched in.
     */
    public String stagingPath(String buildId, BuildKind buildKind, Architecture architecture) {
        Objects.requireNonNull(buildKind, "buildKind");
        Objects.requireNonNull(architecture, "architecture");
        return buildPrefix(buildId) + "/" + buildKind.configValue() + "/"
                + architecture.stagingDirName();
    }

    /** Directory a build's produced binaries are collected from. */
    public String outputPath(String buildId, BuildKind buildKind, Architecture architecture) {
        return stagingPath(buildId, buildKind, architecture) + "/" + OUTPUT_DIR_NAME;
    }

    /** Key of a content-addressed blob. */
    public String casKey(String sha256Hex) {
        if (sha256Hex == null || sha256Hex.isBlank()) {
            throw new IllegalArgumentException("sha256Hex must not be blank");
        }
        return casPrefix + "/" + sha256Hex;
    }

    private static String requireId(String buildId) {
        if (buildId == null || buildId.isBlank()) {
            throw new IllegalArgumentException("buildId must not be blank");
        }
        if (buildId.contains("/") || buildId.contains("..")) {
            throw new IllegalArgumentException(
                    "buildId must not contain path separators or '..': " + buildId);
        }
        return buildId;
    }

    private static String normalizePrefix(String value, String fallback) {
        String candidate = (value == null || value.isBlank()) ? fallback : value.trim();
        while (candidate.startsWith("/")) {
            candidate = candidate.substring(1);
        }
        while (candidate.endsWith("/")) {
            candidate = candidate.substring(0, candidate.length() - 1);
        }
        if (candidate.isEmpty()) {
            throw new IllegalArgumentException("Prefix must not be empty after normalization");
        }
        return candidate;
    }
}
