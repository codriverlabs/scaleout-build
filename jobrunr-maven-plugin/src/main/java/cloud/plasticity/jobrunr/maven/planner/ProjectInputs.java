/*
 * Copyright © 2026 Plasticity.Cloud and CoDriverLabs. All rights reserved.
 */
package cloud.plasticity.jobrunr.maven.planner;

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
        List<String> extraBuildArgs) {

    public ProjectInputs(Path targetDirectory, String finalName, Path projectArtifact,
                         List<Path> runtimeClasspath, String mainClass, String imageName,
                         List<String> extraBuildArgs) {
        this.targetDirectory = Objects.requireNonNull(targetDirectory, "targetDirectory");
        this.finalName = Objects.requireNonNull(finalName, "finalName");
        this.projectArtifact = projectArtifact;
        this.runtimeClasspath = runtimeClasspath == null ? List.of() : List.copyOf(runtimeClasspath);
        this.mainClass = mainClass;
        this.imageName = imageName;
        this.extraBuildArgs = extraBuildArgs == null ? List.of() : List.copyOf(extraBuildArgs);
    }

    /** Directory Quarkus writes its native-image sources into, whether or not it exists. */
    public Path quarkusNativeSourcesDirectory() {
        return targetDirectory.resolve(finalName + "-native-image-source-jar");
    }
}
