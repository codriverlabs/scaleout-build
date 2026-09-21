/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.staging;

import ai.codriverlabs.scaleoutbuild.build.StagingLayout;
import ai.codriverlabs.scaleoutbuild.planner.NativeImageInputPlan;
import ai.codriverlabs.scaleoutbuild.planner.StagedFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/**
 * Stages into a local directory tree.
 *
 * <p>Used by local mode, and equally the stand-in for an S3 Files mount when validating the container
 * contract with a bind mount. Files are hard-linked when the filesystem allows it, so staging a
 * 50 MB classpath costs almost nothing locally; the copy fallback keeps behaviour correct across
 * devices.
 */
public final class LocalStagingSink implements StagingSink {

    private final Path mountRoot;

    public LocalStagingSink(Path mountRoot) {
        this.mountRoot = Objects.requireNonNull(mountRoot, "mountRoot").toAbsolutePath();
    }

    public Path mountRoot() {
        return mountRoot;
    }

    /** Absolute staging root for a relative staging path. */
    public Path resolve(String stagingRelativePath) {
        return mountRoot.resolve(stagingRelativePath).normalize();
    }

    @Override
    public long stage(NativeImageInputPlan plan, String stagingRelativePath) throws IOException {
        Objects.requireNonNull(plan, "plan");
        Path stagingRoot = resolve(stagingRelativePath);
        if (!stagingRoot.startsWith(mountRoot)) {
            throw new IOException("Staging path escapes the mount root: " + stagingRelativePath);
        }
        Files.createDirectories(stagingRoot.resolve(StagingLayout.OUTPUT_DIR_NAME));

        long transferred = 0;
        for (StagedFile file : plan.files()) {
            Path destination = stagingRoot.resolve(file.relativePath()).normalize();
            Files.createDirectories(destination.getParent());
            transferred += link(file.source(), destination);
        }

        if (plan.generatedArgsContent().isPresent()) {
            Path argsFile = stagingRoot.resolve(plan.argsFileName());
            byte[] bytes = plan.generatedArgsContent().get().getBytes(StandardCharsets.UTF_8);
            Files.write(argsFile, bytes);
            transferred += bytes.length;
        }
        return transferred;
    }

    private long link(Path source, Path destination) throws IOException {
        Files.deleteIfExists(destination);
        try {
            Files.createLink(destination, source);
        } catch (IOException | UnsupportedOperationException e) {
            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
        return Files.size(destination);
    }
}
