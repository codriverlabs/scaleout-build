/*
 * Copyright © 2026 Plasticity.Cloud and CoDriverLabs. All rights reserved.
 */
package cloud.plasticity.jobrunr.build.agent;

import cloud.plasticity.jobrunr.build.Architecture;
import cloud.plasticity.jobrunr.build.BuildKind;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Agent configuration, read from the environment.
 *
 * <p>Environment variables rather than command-line arguments because the container's entrypoint is
 * overridden to a fixed {@code java -jar} invocation, while the Step Functions state machine's
 * {@code RunTask.sync} state supplies per-cell values via ECS task overrides. See
 * {@code docs/DESIGN.md} §5 for the task-override contract this mirrors.
 *
 * <p>The agent is single-shot: it reads its one assigned cell, runs it, and exits. There is no
 * polling loop and no job store — the cell's parameters arrive directly as environment variables
 * rather than being claimed from a shared queue.
 *
 * <table border="1">
 *   <caption>Recognised variables</caption>
 *   <tr><th>Variable</th><th>Default</th><th>Meaning</th></tr>
 *   <tr><td>{@code JOBRUNR_BUILD_MOUNT_ROOT}</td><td>{@code /mnt/build}</td>
 *       <td>Root of the S3 Files mount; the staging path resolves against it</td></tr>
 *   <tr><td>{@code JOBRUNR_BUILD_ID}</td><td>none, required</td>
 *       <td>Identifier shared by every cell of the triggering plugin invocation</td></tr>
 *   <tr><td>{@code JOBRUNR_BUILD_KIND}</td><td>none, required</td>
 *       <td>One of {@code native}, {@code native-pgo-instrument}, {@code native-pgo-optimize}
 *           (never {@code jvm} — that build kind never launches a remote task)</td></tr>
 *   <tr><td>{@code JOBRUNR_BUILD_ARCH}</td><td>host architecture</td>
 *       <td>Architecture this cell targets; asserted against the container's actual architecture</td></tr>
 *   <tr><td>{@code JOBRUNR_BUILD_STAGING_RELATIVE_PATH}</td><td>none, required</td>
 *       <td>Staging root relative to the mount, and the {@code native-image} working directory</td></tr>
 *   <tr><td>{@code JOBRUNR_BUILD_ARG_FILE_NAME}</td><td>{@code native-image.args}</td>
 *       <td>Name of the argument file inside the staging root</td></tr>
 *   <tr><td>{@code JOBRUNR_BUILD_PROFILE_RELATIVE_PATH}</td><td>none</td>
 *       <td>Path to the {@code .iprof} profile inside the staging root; required only for
 *           {@code native-pgo-optimize}</td></tr>
 *   <tr><td>{@code JOBRUNR_BUILD_EXPECTED_ARTIFACTS}</td><td>none</td>
 *       <td>Comma-separated artifact file names to look for; empty means discover by scanning</td></tr>
 *   <tr><td>{@code JOBRUNR_BUILD_EXTRA_NATIVE_IMAGE_ARGS}</td><td>none</td>
 *       <td>Extra arguments appended after the argfile and build-kind flags, space-separated
 *           (so an argument containing a space cannot be expressed here)</td></tr>
 *   <tr><td>{@code JOBRUNR_BUILD_TIMEOUT_MINUTES}</td><td>0 (no timeout)</td>
 *       <td>Soft timeout applied to the {@code native-image} process</td></tr>
 *   <tr><td>{@code JOBRUNR_BUILD_NATIVE_IMAGE}</td><td>{@code native-image}</td>
 *       <td>Command that runs native-image, space-separated</td></tr>
 *   <tr><td>{@code JOBRUNR_BUILD_TEMP_DIR}</td><td>{@code /tmp}</td>
 *       <td>Scratch directory, kept on ephemeral storage rather than the mount</td></tr>
 * </table>
 */
public final class AgentConfig {

    static final String ENV_MOUNT_ROOT = "JOBRUNR_BUILD_MOUNT_ROOT";
    static final String ENV_BUILD_ID = "JOBRUNR_BUILD_ID";
    static final String ENV_BUILD_KIND = "JOBRUNR_BUILD_KIND";
    static final String ENV_ARCH = "JOBRUNR_BUILD_ARCH";
    static final String ENV_STAGING_RELATIVE_PATH = "JOBRUNR_BUILD_STAGING_RELATIVE_PATH";
    static final String ENV_ARG_FILE_NAME = "JOBRUNR_BUILD_ARG_FILE_NAME";
    static final String ENV_PROFILE_RELATIVE_PATH = "JOBRUNR_BUILD_PROFILE_RELATIVE_PATH";
    static final String ENV_EXPECTED_ARTIFACTS = "JOBRUNR_BUILD_EXPECTED_ARTIFACTS";
    static final String ENV_EXTRA_NATIVE_IMAGE_ARGS = "JOBRUNR_BUILD_EXTRA_NATIVE_IMAGE_ARGS";
    static final String ENV_TIMEOUT_MINUTES = "JOBRUNR_BUILD_TIMEOUT_MINUTES";
    static final String ENV_NATIVE_IMAGE = "JOBRUNR_BUILD_NATIVE_IMAGE";
    static final String ENV_TEMP_DIR = "JOBRUNR_BUILD_TEMP_DIR";

    private static final Path DEFAULT_MOUNT_ROOT = Paths.get("/mnt/build");
    private static final Path DEFAULT_TEMP_DIR = Paths.get("/tmp");
    private static final List<String> DEFAULT_NATIVE_IMAGE_COMMAND = List.of("native-image");
    private static final String DEFAULT_ARG_FILE_NAME = "native-image.args";

    private final Path mountRoot;
    private final String buildId;
    private final BuildKind buildKind;
    private final Architecture architecture;
    private final String stagingRelativePath;
    private final String argFileName;
    private final String profileRelativePath;
    private final List<String> expectedArtifacts;
    private final List<String> extraNativeImageArgs;
    private final int timeoutMinutes;
    private final List<String> nativeImageCommand;
    private final Path tempDirectory;

    private AgentConfig(Path mountRoot, String buildId, BuildKind buildKind, Architecture architecture,
                        String stagingRelativePath, String argFileName, String profileRelativePath,
                        List<String> expectedArtifacts, List<String> extraNativeImageArgs,
                        int timeoutMinutes, List<String> nativeImageCommand, Path tempDirectory) {
        this.mountRoot = mountRoot;
        this.buildId = buildId;
        this.buildKind = buildKind;
        this.architecture = architecture;
        this.stagingRelativePath = stagingRelativePath;
        this.argFileName = argFileName;
        this.profileRelativePath = profileRelativePath;
        this.expectedArtifacts = expectedArtifacts;
        this.extraNativeImageArgs = extraNativeImageArgs;
        this.timeoutMinutes = timeoutMinutes;
        this.nativeImageCommand = nativeImageCommand;
        this.tempDirectory = tempDirectory;
    }

    /** Reads configuration from the process environment. */
    public static AgentConfig fromEnvironment() {
        return fromMap(System.getenv());
    }

    /**
     * Reads configuration from an explicit map, which is what makes this unit-testable.
     *
     * @throws IllegalArgumentException naming the offending variable, since a container that starts
     *         with bad configuration should fail immediately and legibly
     */
    public static AgentConfig fromMap(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");

        Path mountRoot = path(environment, ENV_MOUNT_ROOT, DEFAULT_MOUNT_ROOT);
        String buildId = requireValue(environment, ENV_BUILD_ID);
        BuildKind buildKind = buildKind(environment);
        Architecture architecture = architecture(environment, buildKind);
        String stagingRelativePath = requireValue(environment, ENV_STAGING_RELATIVE_PATH);
        String argFileName = value(environment, ENV_ARG_FILE_NAME) == null ? DEFAULT_ARG_FILE_NAME
                : value(environment, ENV_ARG_FILE_NAME);
        String profileRelativePath = value(environment, ENV_PROFILE_RELATIVE_PATH);
        if (buildKind.requiresProfile() && (profileRelativePath == null
                || profileRelativePath.isBlank())) {
            throw new IllegalArgumentException(
                    ENV_PROFILE_RELATIVE_PATH + " must be set for build kind " + buildKind);
        }
        List<String> expectedArtifacts = commaSeparated(environment, ENV_EXPECTED_ARTIFACTS);
        List<String> extraNativeImageArgs = spaceSeparated(environment, ENV_EXTRA_NATIVE_IMAGE_ARGS);
        int timeoutMinutes = nonNegativeInt(environment, ENV_TIMEOUT_MINUTES, 0);
        List<String> nativeImageCommand = command(environment);
        Path tempDirectory = path(environment, ENV_TEMP_DIR, DEFAULT_TEMP_DIR);

        return new AgentConfig(mountRoot, buildId, buildKind, architecture, stagingRelativePath,
                argFileName, profileRelativePath, expectedArtifacts, extraNativeImageArgs,
                timeoutMinutes, nativeImageCommand, tempDirectory);
    }

    public Path mountRoot() {
        return mountRoot;
    }

    public String buildId() {
        return buildId;
    }

    public BuildKind buildKind() {
        return buildKind;
    }

    public Architecture architecture() {
        return architecture;
    }

    public String stagingRelativePath() {
        return stagingRelativePath;
    }

    public String argFileName() {
        return argFileName;
    }

    public String profileRelativePath() {
        return profileRelativePath;
    }

    public List<String> expectedArtifacts() {
        return expectedArtifacts;
    }

    public List<String> extraNativeImageArgs() {
        return extraNativeImageArgs;
    }

    public int timeoutMinutes() {
        return timeoutMinutes;
    }

    public List<String> nativeImageCommand() {
        return nativeImageCommand;
    }

    public Path tempDirectory() {
        return tempDirectory;
    }

    @Override
    public String toString() {
        return "AgentConfig{mountRoot=" + mountRoot
                + ", buildId=" + buildId
                + ", buildKind=" + buildKind
                + ", architecture=" + architecture
                + ", stagingRelativePath=" + stagingRelativePath
                + ", argFileName=" + argFileName
                + ", profileRelativePath=" + profileRelativePath
                + ", nativeImageCommand=" + nativeImageCommand
                + ", tempDirectory=" + tempDirectory
                + '}';
    }

    private static BuildKind buildKind(Map<String, String> environment) {
        String configured = requireValue(environment, ENV_BUILD_KIND);
        BuildKind kind = BuildKind.parse(configured);
        if (kind == BuildKind.JVM) {
            throw new IllegalArgumentException(
                    ENV_BUILD_KIND + " must not be JVM: JVM cells never launch a remote task");
        }
        return kind;
    }

    private static Architecture architecture(Map<String, String> environment, BuildKind buildKind) {
        String configured = value(environment, ENV_ARCH);
        Architecture architecture;
        if (configured != null) {
            architecture = Architecture.parse(configured);
        } else {
            Optional<Architecture> host = Architecture.host();
            if (host.isEmpty()) {
                throw new IllegalArgumentException(
                        ENV_ARCH + " is not set and the host architecture is unrecognised (os.arch="
                                + System.getProperty("os.arch") + ")");
            }
            architecture = host.get();
        }
        if (buildKind.requiresArchitecture() && !architecture.matchesHost()) {
            String hostArch = Architecture.host().map(Enum::name)
                    .orElseGet(() -> "unrecognised (os.arch=" + System.getProperty("os.arch") + ")");
            throw new IllegalArgumentException(
                    ENV_ARCH + " is " + architecture + " but this container is " + hostArch
                            + ". native-image cannot cross-compile; check the task definition's "
                            + "runtimePlatform matches the requested architecture.");
        }
        return architecture;
    }

    private static List<String> command(Map<String, String> environment) {
        String configured = value(environment, ENV_NATIVE_IMAGE);
        if (configured == null) {
            return DEFAULT_NATIVE_IMAGE_COMMAND;
        }
        List<String> tokens = Arrays.stream(configured.trim().split("\\s+")).filter(t -> !t.isBlank())
                .toList();
        if (tokens.isEmpty()) {
            throw new IllegalArgumentException(ENV_NATIVE_IMAGE + " must not be blank");
        }
        return tokens;
    }

    private static List<String> commaSeparated(Map<String, String> environment, String key) {
        String configured = value(environment, key);
        if (configured == null) {
            return List.of();
        }
        return Arrays.stream(configured.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .toList();
    }

    private static List<String> spaceSeparated(Map<String, String> environment, String key) {
        String configured = value(environment, key);
        if (configured == null) {
            return List.of();
        }
        return Arrays.stream(configured.trim().split("\\s+")).filter(s -> !s.isEmpty()).toList();
    }

    private static Path path(Map<String, String> environment, String key, Path fallback) {
        String configured = value(environment, key);
        return configured == null ? fallback : Paths.get(configured);
    }

    private static int nonNegativeInt(Map<String, String> environment, String key, int fallback) {
        String configured = value(environment, key);
        if (configured == null) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(configured.trim());
            if (parsed < 0) {
                throw new IllegalArgumentException(key + " must not be negative, was " + configured);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a number, was '" + configured + "'", e);
        }
    }

    private static String value(Map<String, String> environment, String key) {
        String raw = environment.get(key);
        return raw == null || raw.isBlank() ? null : raw.trim();
    }

    private static String requireValue(Map<String, String> environment, String key) {
        String configured = value(environment, key);
        if (configured == null) {
            throw new IllegalArgumentException(key + " must be set");
        }
        return configured;
    }
}
