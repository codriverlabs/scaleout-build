/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.build;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArtifactCollectorTest {

    @Test
    void findsAnExpectedArtifactInTheOutputDirectoryFirst(@TempDir Path stagingRoot) throws IOException {
        Path outputDir = stagingRoot.resolve(StagingLayout.OUTPUT_DIR_NAME);
        Files.createDirectories(outputDir);
        Files.writeString(outputDir.resolve("app"), "binary-bytes");
        Files.writeString(stagingRoot.resolve("app"), "stale-copy-should-not-be-picked");

        List<Path> found = ArtifactCollector.collect(stagingRoot, List.of("app"), Instant.EPOCH);

        assertThat(found).containsExactly(outputDir.resolve("app").toAbsolutePath());
    }

    @Test
    void fallsBackToScanningWhenNoExpectedNamesAreGiven(@TempDir Path stagingRoot) throws IOException {
        Instant before = Instant.now().minusSeconds(5);
        Files.writeString(stagingRoot.resolve("native-image.args"), "ignored by suffix");
        Files.writeString(stagingRoot.resolve("runner.jar"), "ignored by suffix");
        Path binary = stagingRoot.resolve("my-app");
        Files.writeString(binary, "the real binary");

        List<Path> found = ArtifactCollector.collect(stagingRoot, List.of(), before);

        assertThat(found).containsExactly(binary.toAbsolutePath());
    }

    @Test
    void ignoresFilesModifiedBeforeTheBuildStarted(@TempDir Path stagingRoot) throws IOException {
        Path staleFile = stagingRoot.resolve("leftover-binary");
        Files.writeString(staleFile, "old");
        Instant cutoff = Files.getLastModifiedTime(staleFile).toInstant().plusSeconds(60);

        List<Path> found = ArtifactCollector.collect(stagingRoot, List.of(), cutoff);

        assertThat(found).isEmpty();
    }

    @Test
    void returnsEmptyWhenNothingIsFound(@TempDir Path stagingRoot) {
        List<Path> found = ArtifactCollector.collect(stagingRoot, List.of("missing"), Instant.EPOCH);

        assertThat(found).isEmpty();
    }
}
