/*
 * Copyright © 2026 Plasticity.Cloud and CoDriverLabs. All rights reserved.
 */
package cloud.plasticity.jobrunr.build;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * A target CPU architecture for a native-image build.
 *
 * <p>GraalVM {@code native-image} cannot cross-compile, so every architecture needs a build on
 * matching hardware — see {@code docs/DESIGN.md} §3 for the full build matrix this feeds into.
 */
public enum Architecture {

    X86_64("X86_64", "linux-x86_64", "x86_64", List.of("amd64", "x86_64", "x86-64", "x64")),
    ARM64("ARM64", "linux-arm64", "arm64", List.of("aarch64", "arm64", "arm-64"));

    private final String ecsCpuArchitecture;
    private final String artifactClassifier;
    private final String schemaSuffix;
    private final List<String> osArchAliases;

    Architecture(String ecsCpuArchitecture, String artifactClassifier, String schemaSuffix,
                 List<String> osArchAliases) {
        this.ecsCpuArchitecture = ecsCpuArchitecture;
        this.artifactClassifier = artifactClassifier;
        this.schemaSuffix = schemaSuffix;
        this.osArchAliases = osArchAliases;
    }

    /** Value for the ECS task definition {@code runtimePlatform.cpuArchitecture} field. */
    public String ecsCpuArchitecture() {
        return ecsCpuArchitecture;
    }

    /** Maven artifact classifier used when attaching the produced binary to the reactor. */
    public String artifactClassifier() {
        return artifactClassifier;
    }

    /**
     * Suffix used for this architecture's staging directory and task-definition family naming,
     * such as {@code x86_64}.
     */
    public String schemaSuffix() {
        return schemaSuffix;
    }

    /** Directory name used for this architecture inside a build's staging area. */
    public String stagingDirName() {
        return schemaSuffix;
    }

    /** The architecture of the JVM running this code, if recognised. */
    public static Optional<Architecture> host() {
        return fromOsArch(System.getProperty("os.arch"));
    }

    /**
     * Resolves a JVM {@code os.arch} value (or any common alias) to an architecture.
     *
     * @return empty when the value is unknown, so callers can report the raw value rather than
     *         silently guessing
     */
    public static Optional<Architecture> fromOsArch(String osArch) {
        if (osArch == null || osArch.isBlank()) {
            return Optional.empty();
        }
        String normalized = osArch.trim().toLowerCase(Locale.ROOT);
        for (Architecture candidate : values()) {
            if (candidate.osArchAliases.contains(normalized)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /** True when this architecture matches the host the JVM is running on. */
    public boolean matchesHost() {
        return host().filter(this::equals).isPresent();
    }

    /**
     * Parses a user-supplied configuration value such as {@code arm64}, {@code ARM64} or
     * {@code aarch64}.
     *
     * @throws IllegalArgumentException with the accepted values, since this is reached from plugin
     *         configuration where a clear message is worth more than an {@link Optional}
     */
    public static Architecture parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Architecture must not be blank; expected one of: " + acceptedValues());
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (Architecture candidate : values()) {
            if (candidate.name().toLowerCase(Locale.ROOT).equals(normalized)
                    || candidate.osArchAliases.contains(normalized)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException(
                "Unknown architecture '" + value + "'; expected one of: " + acceptedValues());
    }

    private static String acceptedValues() {
        return String.join(", ", List.of(X86_64.name(), ARM64.name(), "amd64", "aarch64"));
    }
}
