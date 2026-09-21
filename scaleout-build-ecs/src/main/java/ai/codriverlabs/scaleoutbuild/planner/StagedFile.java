/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.planner;

import java.nio.file.Path;
import java.util.Objects;

/**
 * One file to place into a build's staging area.
 *
 * @param relativePath destination relative to the staging root, POSIX-style, so the same value works
 *                     as an S3 key and as a path inside the mounted builder container
 * @param source       local file to copy or upload
 */
public record StagedFile(String relativePath, Path source) {

    public StagedFile(String relativePath, Path source) {
        Objects.requireNonNull(relativePath, "relativePath");
        Objects.requireNonNull(source, "source");
        if (relativePath.isBlank()) {
            throw new IllegalArgumentException("relativePath must not be blank");
        }
        if (relativePath.startsWith("/") || relativePath.contains("..")) {
            throw new IllegalArgumentException(
                    "relativePath must be relative and must not contain '..': " + relativePath);
        }
        this.relativePath = relativePath;
        this.source = source;
    }
}
