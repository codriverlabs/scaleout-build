/*
 * Copyright © 2026 Plasticity.Cloud and CoDriverLabs. All rights reserved.
 */
package cloud.plasticity.jobrunr.build.agent;

import cloud.plasticity.jobrunr.build.Architecture;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Agent configuration, read from the environment.
 *
 * <p>Environment variables rather than command-line arguments because the container's entrypoint is
 * overridden to a fixed {@code java -jar} invocation, while the ECS task definition supplies
 * per-architecture values.
 *
 * <table border="1">
 *   <caption>Recognised variables</caption>
 *   <tr><th>Variable</th><th>Default</th><th>Meaning</th></tr>
 *   <tr><td>{@code JOBRUNR_BUILD_MOUNT_ROOT}</td><td>{@code /mnt/build}</td>
 *       <td>Root of the S3 Files mount; job staging paths resolve against it</td></tr>
 *   <tr><td>{@code JOBRUNR_BUILD_ARCH}</td><td>host architecture</td>
 *       <td>Architecture this worker serves, selecting its JobRunr schema</td></tr>
 *   <tr><td>{@code JOBRUNR_BUILD_NATIVE_IMAGE}</td><td>{@code native-image}</td>
 *       <td>Command that runs native-image, space-separated</td></tr>
 *   <tr><td>{@code JOBRUNR_BUILD_TEMP_DIR}</td><td>{@code /tmp}</td>
 *       <td>Scratch directory, kept on ephemeral storage rather than the mount</td></tr>
 *   <tr><td>{@code JOBRUNR_BUILD_IDLE_TIMEOUT_SECONDS}</td><td>300</td>
 *       <td>Exit if no job is claimed within this window, so a task that lost its job to a
 *           concurrent worker stops billing</td></tr>
 *   <tr><td>{@code JOBRUNR_BUILD_MAX_DURATION_MINUTES}</td><td>120</td>
 *       <td>Upper bound on total worker lifetime</td></tr>
 *   <tr><td>{@code JOBRUNR_BUILD_JOBS}</td><td>1</td>
 *       <td>Number of jobs to process before exiting</td></tr>
 * </table>
 */
public final class AgentConfig {

    static final String ENV_MOUNT_ROOT = "JOBRUNR_BUILD_MOUNT_ROOT";
    static final String ENV_ARCH = "JOBRUNR_BUILD_ARCH";
    static final String ENV_NATIVE_IMAGE = "JOBRUNR_BUILD_NATIVE_IMAGE";
    static final String ENV_TEMP_DIR = "JOBRUNR_BUILD_TEMP_DIR";
    static final String ENV_IDLE_TIMEOUT_SECONDS = "JOBRUNR_BUILD_IDLE_TIMEOUT_SECONDS";
    static final String ENV_MAX_DURATION_MINUTES = "JOBRUNR_BUILD_MAX_DURATION_MINUTES";
    static final String ENV_JOBS = "JOBRUNR_BUILD_JOBS";

    private static final Path DEFAULT_MOUNT_ROOT = Paths.get("/mnt/build");
    private static final Path DEFAULT_TEMP_DIR = Paths.get("/tmp");
    private static final List<String> DEFAULT_NATIVE_IMAGE_COMMAND = List.of("native-image");
    private static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration DEFAULT_MAX_DURATION = Duration.ofHours(2);

    private final Path mountRoot;
    private final Architecture architecture;
    private final List<String> nativeImageCommand;
    private final Path tempDirectory;
    private final Duration idleTimeout;
    private final Duration maxDuration;
    private final int jobsToProcess;

    private AgentConfig(Path mountRoot, Architecture architecture, List<String> nativeImageCommand,
                        Path tempDirectory, Duration idleTimeout, Duration maxDuration,
                        int jobsToProcess) {
        this.mountRoot = mountRoot;
        this.architecture = architecture;
        this.nativeImageCommand = nativeImageCommand;
        this.tempDirectory = tempDirectory;
        this.idleTimeout = idleTimeout;
        this.maxDuration = maxDuration;
        this.jobsToProcess = jobsToProcess;
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
        Architecture architecture = architecture(environment);
        List<String> nativeImageCommand = command(environment);
        Path tempDirectory = path(environment, ENV_TEMP_DIR, DEFAULT_TEMP_DIR);
        Duration idleTimeout = seconds(environment, ENV_IDLE_TIMEOUT_SECONDS, DEFAULT_IDLE_TIMEOUT);
        Duration maxDuration = minutes(environment, ENV_MAX_DURATION_MINUTES, DEFAULT_MAX_DURATION);
        int jobs = positiveInt(environment, ENV_JOBS, 1);

        return new AgentConfig(mountRoot, architecture, nativeImageCommand, tempDirectory,
                idleTimeout, maxDuration, jobs);
    }

    public Path mountRoot() {
        return mountRoot;
    }

    public Architecture architecture() {
        return architecture;
    }

    public List<String> nativeImageCommand() {
        return nativeImageCommand;
    }

    public Path tempDirectory() {
        return tempDirectory;
    }

    public Duration idleTimeout() {
        return idleTimeout;
    }

    public Duration maxDuration() {
        return maxDuration;
    }

    public int jobsToProcess() {
        return jobsToProcess;
    }

    @Override
    public String toString() {
        return "AgentConfig{mountRoot=" + mountRoot
                + ", architecture=" + architecture
                + ", nativeImageCommand=" + nativeImageCommand
                + ", tempDirectory=" + tempDirectory
                + ", idleTimeout=" + idleTimeout
                + ", maxDuration=" + maxDuration
                + ", jobsToProcess=" + jobsToProcess
                + '}';
    }

    private static Architecture architecture(Map<String, String> environment) {
        String configured = value(environment, ENV_ARCH);
        if (configured != null) {
            return Architecture.parse(configured);
        }
        Optional<Architecture> host = Architecture.host();
        if (host.isEmpty()) {
            throw new IllegalArgumentException(
                    ENV_ARCH + " is not set and the host architecture is unrecognised (os.arch="
                            + System.getProperty("os.arch") + ")");
        }
        return host.get();
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

    private static Path path(Map<String, String> environment, String key, Path fallback) {
        String configured = value(environment, key);
        return configured == null ? fallback : Paths.get(configured);
    }

    private static Duration seconds(Map<String, String> environment, String key, Duration fallback) {
        String configured = value(environment, key);
        return configured == null ? fallback : Duration.ofSeconds(parseLong(key, configured));
    }

    private static Duration minutes(Map<String, String> environment, String key, Duration fallback) {
        String configured = value(environment, key);
        return configured == null ? fallback : Duration.ofMinutes(parseLong(key, configured));
    }

    private static int positiveInt(Map<String, String> environment, String key, int fallback) {
        String configured = value(environment, key);
        if (configured == null) {
            return fallback;
        }
        long parsed = parseLong(key, configured);
        if (parsed < 1 || parsed > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(key + " must be a positive integer, was " + configured);
        }
        return (int) parsed;
    }

    private static long parseLong(String key, String rawValue) {
        try {
            long parsed = Long.parseLong(rawValue.trim());
            if (parsed < 0) {
                throw new IllegalArgumentException(key + " must not be negative, was " + rawValue);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a number, was '" + rawValue + "'", e);
        }
    }

    private static String value(Map<String, String> environment, String key) {
        String raw = environment.get(key);
        return raw == null || raw.isBlank() ? null : raw.trim();
    }
}
