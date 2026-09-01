/*
 * Copyright © 2026 Plasticity.Cloud and CoDriverLabs. All rights reserved.
 */
package cloud.plasticity.jobrunr.build;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Locates the binaries a native-image run produced.
 *
 * <p>Named artifacts are the reliable path and are looked up directly. Scanning is a fallback for
 * builds whose argfile decides the output name itself — which is the normal case when Quarkus
 * generated the argfile and we pass it through verbatim.
 */
public final class ArtifactCollector {

    /**
     * Suffixes that native-image writes alongside a binary but which are never the artifact itself.
     * {@code .debug} is intentionally absent: separate debug info is worth returning.
     */
    private static final Set<String> IGNORED_SUFFIXES =
            Set.of(".jar", ".args", ".json", ".txt", ".log", ".o", ".a", ".properties", ".class");

    private ArtifactCollector() {
    }

    /**
     * @param stagingRoot      working directory of the build
     * @param expectedNames    artifact file names to look for first; may be empty
     * @param notModifiedBefore only files touched at or after this instant are considered when
     *                          scanning, so pre-existing inputs are never mistaken for output
     */
    public static List<Path> collect(Path stagingRoot, List<String> expectedNames,
                                     Instant notModifiedBefore) {        Set<Path> found = new LinkedHashSet<>();
        Path outputDir = stagingRoot.resolve(StagingLayout.OUTPUT_DIR_NAME);

        for (String name : expectedNames) {
            for (Path candidate : List.of(outputDir.resolve(name), stagingRoot.resolve(name))) {
                if (Files.isRegularFile(candidate)) {
                    found.add(candidate.toAbsolutePath());
                    break;
                }
            }
        }
        if (!found.isEmpty()) {
            return List.copyOf(found);
        }

        found.addAll(scan(outputDir, notModifiedBefore));
        found.addAll(scan(stagingRoot, notModifiedBefore));
        return List.copyOf(found);
    }

    private static List<Path> scan(Path directory, Instant notModifiedBefore) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(directory)) {
            List<Path> candidates = new ArrayList<>();
            for (Path entry : entries.toList()) {
                if (!Files.isRegularFile(entry) || isIgnored(entry)) {
                    continue;
                }
                if (Files.getLastModifiedTime(entry).toInstant().isBefore(notModifiedBefore)) {
                    continue;
                }
                candidates.add(entry.toAbsolutePath());
            }
            return candidates;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to scan " + directory + " for build artifacts", e);
        }
    }

    private static boolean isIgnored(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return IGNORED_SUFFIXES.stream().anyMatch(name::endsWith);
    }
}
