/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.planner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * The subset of a Maven project the input planner needs.
 *
 * <p>Kept separate from {@code MavenProject} so planning can be unit-tested without constructing a
 * Maven session.
 *
 * @param targetDirectory  the project's build output directory
 * @param finalName        build final name, e.g. {@code operator-controller-0.1.0-SNAPSHOT}
 * @param projectArtifact  packaged project artifact, or {@code null} when {@code package} has not run
 * @param runtimeClasspath resolved runtime-scope classpath entries
 * @param mainClass        explicit main class, or {@code null} to read it from the artifact manifest
 * @param imageName        explicit output binary name, or {@code null} to derive it
 * @param extraBuildArgs   additional native-image arguments for derived mode
 */
public record ProjectInputs(
        Path targetDirectory,
        String finalName,
        Path projectArtifact,
        List<Path> runtimeClasspath,
        String mainClass,
        String imageName,
        List<String> extraBuildArgs,
        Path argsFileDirectory) {

    /** Keeps existing callers working; no explicit argfile directory. */
    public ProjectInputs(Path targetDirectory, String finalName, Path projectArtifact,
                         List<Path> runtimeClasspath, String mainClass, String imageName,
                         List<String> extraBuildArgs) {
        this(targetDirectory, finalName, projectArtifact, runtimeClasspath, mainClass, imageName,
                extraBuildArgs, null);
    }

    public ProjectInputs(Path targetDirectory, String finalName, Path projectArtifact,
                         List<Path> runtimeClasspath, String mainClass, String imageName,
                         List<String> extraBuildArgs, Path argsFileDirectory) {
        this.argsFileDirectory = argsFileDirectory;
        this.targetDirectory = Objects.requireNonNull(targetDirectory, "targetDirectory");
        this.finalName = Objects.requireNonNull(finalName, "finalName");
        this.projectArtifact = projectArtifact;
        this.runtimeClasspath = runtimeClasspath == null ? List.of() : List.copyOf(runtimeClasspath);
        this.mainClass = mainClass;
        this.imageName = imageName;
        this.extraBuildArgs = extraBuildArgs == null ? List.of() : List.copyOf(extraBuildArgs);
    }

    /**
     * Candidate directories Quarkus writes its native-image sources into, newest layout first.
     *
     * <p>Two names, because Quarkus changed it. Current versions (verified against 3.39.4) write
     * {@code target/native-sources/}; older ones wrote
     * {@code target/<finalName>-native-image-source-jar/}. Probing both means the plugin works across the
     * range rather than silently falling through to derived mode on a modern project -- which is what it
     * did before, and which is worse than failing: a derived argfile omits the
     * {@code --features=io.quarkus.runner.Feature} and reflection configuration that Quarkus augmentation
     * generates, so the build either fails obscurely or produces a binary that breaks at run time.
     */
    public List<Path> quarkusNativeSourcesCandidates() {
        return List.of(
                targetDirectory.resolve("native-sources"),
                targetDirectory.resolve(finalName + "-native-image-source-jar"));
    }

    /** First candidate directory that exists, or the preferred one if none do. */
    public Path quarkusNativeSourcesDirectory() {
        return quarkusNativeSourcesCandidates().stream()
                .filter(Files::isDirectory)
                .findFirst()
                .orElseGet(() -> quarkusNativeSourcesCandidates().get(0));
    }
}
