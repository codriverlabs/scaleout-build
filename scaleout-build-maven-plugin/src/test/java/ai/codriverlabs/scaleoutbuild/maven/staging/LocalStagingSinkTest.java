/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.maven.staging;

import static org.assertj.core.api.Assertions.assertThat;

import ai.codriverlabs.scaleoutbuild.build.StagingLayout;
import ai.codriverlabs.scaleoutbuild.maven.planner.NativeImageInputPlan;
import ai.codriverlabs.scaleoutbuild.maven.planner.StagedFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalStagingSinkTest {

    @Test
    void stagesFilesAndWritesTheGeneratedArgfile(@TempDir Path sourceDir, @TempDir Path mountRoot)
            throws IOException {
        Path artifact = sourceDir.resolve("app.jar");
        Files.writeString(artifact, "fake-jar-bytes");
        Path dependency = sourceDir.resolve("dep.jar");
        Files.writeString(dependency, "fake-dep-bytes");

        NativeImageInputPlan plan = NativeImageInputPlan.generated(
                List.of(new StagedFile("app.jar", artifact), new StagedFile("lib/dep.jar", dependency)),
                "-cp\napp.jar:lib/dep.jar\n-o\noutput/app\ndemo.Main\n", List.of("app"));

        LocalStagingSink sink = new LocalStagingSink(mountRoot);
        long transferred = sink.stage(plan, "builds/b1/x86_64");

        Path stagingRoot = mountRoot.resolve("builds/b1/x86_64");
        assertThat(stagingRoot.resolve("app.jar")).exists().hasContent("fake-jar-bytes");
        assertThat(stagingRoot.resolve("lib/dep.jar")).exists().hasContent("fake-dep-bytes");
        assertThat(stagingRoot.resolve(StagingLayout.OUTPUT_DIR_NAME)).isDirectory();
        assertThat(stagingRoot.resolve("native-image.args")).exists()
                .hasContent("-cp\napp.jar:lib/dep.jar\n-o\noutput/app\ndemo.Main\n");
        assertThat(transferred).isGreaterThan(0);
    }

    @Test
    void rejectsAStagingPathThatEscapesTheMountRoot(@TempDir Path mountRoot) {
        NativeImageInputPlan plan = NativeImageInputPlan.generated(List.of(), "content", List.of());
        LocalStagingSink sink = new LocalStagingSink(mountRoot);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> sink.stage(plan, "../escaped"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("escapes the mount root");
    }
}
