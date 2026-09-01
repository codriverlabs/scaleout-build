/*
 * Copyright © 2026 Plasticity.Cloud and CoDriverLabs. All rights reserved.
 */
package cloud.plasticity.jobrunr.build;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Where a worker finds its inputs and which command builds them.
 *
 * <p>The mount root is the only absolute path a worker needs: every job payload references its
 * staging area relative to it. In the container that root is the S3 Files mount; locally it is a
 * directory under {@code target/}.
 */
public final class BuildEnvironment {

    private final Path mountRoot;
    private final List<String> nativeImageCommand;
    private final Path tempDirectory;

    private BuildEnvironment(Builder builder) {
        this.mountRoot = builder.mountRoot;
        this.nativeImageCommand = List.copyOf(builder.nativeImageCommand);
        this.tempDirectory = builder.tempDirectory;
    }

    /** Root the job's {@code stagingRelativePath} is resolved against. */
    public Path mountRoot() {
        return mountRoot;
    }

    /**
     * Command that invokes native-image, as a token list. Usually {@code ["native-image"]}; a
     * container wrapper such as {@code ["docker", "run", ...]} is equally valid, which is how a host
     * without a local GraalVM can still drive a real build.
     */
    public List<String> nativeImageCommand() {
        return nativeImageCommand;
    }

    /**
     * Directory for the builder's scratch files, or {@code null} to inherit. Kept off the shared
     * mount on purpose: native-image writes heavily to temp, and that traffic should stay on local
     * ephemeral storage rather than crossing a network file system.
     */
    public Path tempDirectory() {
        return tempDirectory;
    }

    /**
     * Resolves and validates the staging directory for a job.
     *
     * @throws BuildFailedException when the directory is missing, which in practice means the
     *         staged inputs never arrived
     */
    public Path resolveStagingRoot(BuildJobRequest request) throws BuildFailedException {
        Path staging = mountRoot.resolve(request.getStagingRelativePath()).normalize();
        if (!staging.startsWith(mountRoot.normalize())) {
            throw new BuildFailedException(
                    "Staging path escapes the mount root: " + request.getStagingRelativePath());
        }
        if (!Files.isDirectory(staging)) {
            throw new BuildFailedException("Staging directory not found: " + staging);
        }
        return staging;
    }

    public static Builder builder(Path mountRoot) {
        return new Builder(mountRoot);
    }

    /** Builder for {@link BuildEnvironment}. */
    public static final class Builder {
        private final Path mountRoot;
        private List<String> nativeImageCommand = List.of("native-image");
        private Path tempDirectory;

        private Builder(Path mountRoot) {
            this.mountRoot = Objects.requireNonNull(mountRoot, "mountRoot").toAbsolutePath();
        }

        public Builder nativeImageCommand(List<String> nativeImageCommand) {
            if (nativeImageCommand == null || nativeImageCommand.isEmpty()) {
                throw new IllegalArgumentException("nativeImageCommand must not be empty");
            }
            this.nativeImageCommand = nativeImageCommand;
            return this;
        }

        public Builder tempDirectory(Path tempDirectory) {
            this.tempDirectory = tempDirectory;
            return this;
        }

        public BuildEnvironment build() {
            return new BuildEnvironment(this);
        }
    }
}
