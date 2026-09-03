/*
 * Copyright © 2026 Plasticity.Cloud and CoDriverLabs. All rights reserved.
 */
package cloud.plasticity.jobrunr.build;

import java.util.List;
import java.util.Locale;

/**
 * What kind of build one matrix cell produces.
 *
 * <p>See {@code docs/DESIGN.md} §3. {@link #JVM} is portable bytecode with no {@code native-image}
 * involved and no architecture dimension. The other three are all {@code native-image} builds,
 * each requiring an architecture (native-image cannot cross-compile).
 *
 * <p>{@link #NATIVE_PGO_INSTRUMENT} and {@link #NATIVE_PGO_OPTIMIZE} are independent,
 * separately-triggerable build kinds, not steps of a pipeline this plugin orchestrates. Running the
 * instrumented binary against a representative workload to collect the {@code .iprof} profile that
 * {@code NATIVE_PGO_OPTIMIZE} consumes happens entirely outside this tool.
 */
public enum BuildKind {

    /** Plain jar; no {@code native-image} invocation, no architecture dimension. */
    JVM(false, false, "jvm"),

    /** Plain {@code native-image} build, no profile-guided optimization. */
    NATIVE(true, false, "native"),

    /**
     * {@code native-image --pgo-instrument}. Produces an instrumented binary. This plugin's
     * responsibility ends at producing that binary — collecting a profile from it is out of scope.
     */
    NATIVE_PGO_INSTRUMENT(true, false, "native-pgo-instrument"),

    /**
     * {@code native-image --pgo=<profile>}. Requires a {@code .iprof} profile supplied by the
     * caller, staged like any other build input.
     */
    NATIVE_PGO_OPTIMIZE(true, true, "native-pgo-optimize");

    private final boolean requiresArchitecture;
    private final boolean requiresProfile;
    private final String configValue;

    BuildKind(boolean requiresArchitecture, boolean requiresProfile, String configValue) {
        this.requiresArchitecture = requiresArchitecture;
        this.requiresProfile = requiresProfile;
        this.configValue = configValue;
    }

    /** True for every kind except {@link #JVM}: {@code native-image} cannot cross-compile. */
    public boolean requiresArchitecture() {
        return requiresArchitecture;
    }

    /** True only for {@link #NATIVE_PGO_OPTIMIZE}: it needs a {@code .iprof} input. */
    public boolean requiresProfile() {
        return requiresProfile;
    }

    /**
     * Extra {@code native-image} flags for this build kind, appended before the argfile reference.
     *
     * @param profileRelativePath path to the {@code .iprof} file relative to the staging root;
     *                            required (and only used) for {@link #NATIVE_PGO_OPTIMIZE}
     */
    public List<String> nativeImageFlags(String profileRelativePath) {
        return switch (this) {
            case JVM -> throw new IllegalStateException(
                    "BuildKind.JVM never invokes native-image; this should not be called");
            case NATIVE -> List.of();
            case NATIVE_PGO_INSTRUMENT -> List.of("--pgo-instrument");
            case NATIVE_PGO_OPTIMIZE -> {
                if (profileRelativePath == null || profileRelativePath.isBlank()) {
                    throw new IllegalArgumentException(
                            "NATIVE_PGO_OPTIMIZE requires a profile path; none was supplied");
                }
                yield List.of("--pgo=" + profileRelativePath);
            }
        };
    }

    /** Value used in plugin configuration and task-override environment variables. */
    public String configValue() {
        return configValue;
    }

    /**
     * Parses a user-supplied configuration value such as {@code native}, {@code NATIVE},
     * {@code native-pgo-optimize}, or the enum name.
     *
     * @throws IllegalArgumentException with the accepted values, since this is reached from plugin
     *         configuration where a clear message is worth more than silent fallback
     */
    public static BuildKind parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Build kind must not be blank; expected one of: " + acceptedValues());
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (BuildKind candidate : values()) {
            if (candidate.configValue.equals(normalized)
                    || candidate.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException(
                "Unknown build kind '" + value + "'; expected one of: " + acceptedValues());
    }

    private static String acceptedValues() {
        StringBuilder sb = new StringBuilder();
        for (BuildKind kind : values()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(kind.configValue);
        }
        return sb.toString();
    }
}
