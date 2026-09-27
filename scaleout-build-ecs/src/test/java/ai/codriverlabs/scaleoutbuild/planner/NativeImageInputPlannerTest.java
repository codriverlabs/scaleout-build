/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.planner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Covers the three input-mode cases documented on {@link NativeImageInputPlanner}. */
class NativeImageInputPlannerTest {

    private final NativeImageInputPlanner planner = new NativeImageInputPlanner();

    @Test
    void passesThroughAQuarkusNativeSourcesArgfileVerbatim(@TempDir Path targetDirectory)
            throws IOException, InputPlanningException {
        String finalName = "operator-controller-0.1.0-SNAPSHOT";
        Path sourcesDir = targetDirectory.resolve(finalName + "-native-image-source-jar");
        Files.createDirectories(sourcesDir.resolve("lib"));
        Files.writeString(sourcesDir.resolve("native-image.args"), "-cp\nrunner.jar\n-o\noutput/app\n");
        Files.writeString(sourcesDir.resolve("runner.jar"), "fake-jar-bytes");
        Files.writeString(sourcesDir.resolve("lib/dep.jar"), "fake-dep-bytes");

        ProjectInputs inputs = new ProjectInputs(targetDirectory, finalName, null, List.of(), null, null,
                List.of());

        NativeImageInputPlan plan = planner.plan(inputs);

        assertThat(plan.mode()).isEqualTo(InputMode.STAGED_ARGS_FILE);
        assertThat(plan.argsFileName()).isEqualTo("native-image.args");
        assertThat(plan.generatedArgsContent()).isEmpty();
        assertThat(plan.files()).extracting(StagedFile::relativePath)
                .containsExactlyInAnyOrder("native-image.args", "runner.jar", "lib/dep.jar");
    }

    @Test
    void failsFastWhenSourcesDirectoryExistsWithoutAnArgfile(@TempDir Path targetDirectory)
            throws IOException {
        String finalName = "operator-cli-0.1.0-SNAPSHOT";
        Path sourcesDir = targetDirectory.resolve(finalName + "-native-image-source-jar");
        Files.createDirectories(sourcesDir);
        Files.writeString(sourcesDir.resolve("runner.jar"), "fake-jar-bytes");

        ProjectInputs inputs = new ProjectInputs(targetDirectory, finalName, null, List.of(), null, null,
                List.of());

        assertThatThrownBy(() -> planner.plan(inputs))
                .isInstanceOf(InputPlanningException.class)
                .hasMessageContaining("native-sources")
                .hasMessageContaining("mvn package -Dquarkus.package.jar.type=native-sources");
    }

    @Test
    void derivesAnArgfileFromThePackagedArtifactAndRuntimeClasspath(@TempDir Path targetDirectory)
            throws IOException, InputPlanningException {
        String finalName = "demo-0.1.0-SNAPSHOT";
        Path artifact = targetDirectory.resolve(finalName + ".jar");
        writeJarWithMainClass(artifact, "demo.Main");
        Path dependency = targetDirectory.resolve("dep-1.0.jar");
        Files.writeString(dependency, "fake-dep-bytes");

        ProjectInputs inputs = new ProjectInputs(targetDirectory, finalName, artifact,
                List.of(dependency), null, null, List.of("--enable-preview"));

        NativeImageInputPlan plan = planner.plan(inputs);

        assertThat(plan.mode()).isEqualTo(InputMode.GENERATED_ARGS_FILE);
        assertThat(plan.generatedArgsContent()).isPresent();
        String args = plan.generatedArgsContent().get();
        assertThat(args).contains("-cp").contains(finalName + ".jar:lib/dep-1.0.jar")
                .contains("--enable-preview").contains("demo.Main").contains("-o").contains(finalName);
        assertThat(plan.files()).extracting(StagedFile::relativePath)
                .containsExactlyInAnyOrder(finalName + ".jar", "lib/dep-1.0.jar");
        assertThat(plan.expectedArtifacts()).containsExactly(finalName);
    }

    @Test
    void derivedModeFailsWithoutAPackagedArtifact(@TempDir Path targetDirectory) {
        ProjectInputs inputs = new ProjectInputs(targetDirectory, "demo-0.1.0-SNAPSHOT", null, List.of(),
                null, null, List.of());

        assertThatThrownBy(() -> planner.plan(inputs))
                .isInstanceOf(InputPlanningException.class)
                .hasMessageContaining("No packaged artifact");
    }

    @Test
    void derivedModeFailsWithoutAMainClassOrManifestEntry(@TempDir Path targetDirectory)
            throws IOException {
        Path artifact = targetDirectory.resolve("demo.jar");
        writeJarWithMainClass(artifact, null);

        ProjectInputs inputs = new ProjectInputs(targetDirectory, "demo-0.1.0-SNAPSHOT", artifact,
                List.of(), null, null, List.of());

        assertThatThrownBy(() -> planner.plan(inputs))
                .isInstanceOf(InputPlanningException.class)
                .hasMessageContaining("main class");
    }

    @Test
    void derivedModeRejectsDirectoryClasspathEntriesOutsideTheProject(@TempDir Path targetDirectory,
                                                                      @TempDir Path otherDirectory)
            throws IOException {
        Path artifact = targetDirectory.resolve("demo.jar");
        writeJarWithMainClass(artifact, "demo.Main");

        ProjectInputs inputs = new ProjectInputs(targetDirectory, "demo-0.1.0-SNAPSHOT", artifact,
                List.of(otherDirectory), null, null, List.of());

        assertThatThrownBy(() -> planner.plan(inputs))
                .isInstanceOf(InputPlanningException.class)
                .hasMessageContaining("is a directory");
    }

    private static void writeJarWithMainClass(Path jarPath, String mainClass) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (mainClass != null) {
            manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainClass);
        }
        try (OutputStream out = Files.newOutputStream(jarPath);
                JarOutputStream jarOut = new JarOutputStream(out, manifest)) {
            jarOut.putNextEntry(new java.util.zip.ZipEntry("marker.txt"));
            jarOut.write("marker".getBytes(StandardCharsets.UTF_8));
            jarOut.closeEntry();
        }
    }
}
