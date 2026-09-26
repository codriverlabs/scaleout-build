/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.planner;

import ai.codriverlabs.scaleoutbuild.build.StagingLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.io.UncheckedIOException;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Plain GraalVM: derives an argument file from the packaged artifact and the resolved runtime classpath.
 *
 * <p>The fallback, and correct only when nothing else generated arguments. For an AOT-processed
 * application — Quarkus, Spring Boot, Helidon — a derived argfile omits the generated feature
 * registrations and reflection configuration those frameworks depend on, so it produces a binary that may
 * build cleanly and then fail at run time. The strategies ahead of this one in
 * {@link NativeImageInputPlanner} exist to make sure it is not reached in those cases.
 *
 * <p>Verified end to end against the example application.
 */
public final class DerivedClasspathStrategy implements InputPlanStrategy {

    @Override
    public String name() {
        return "derived from runtime classpath (plain GraalVM)";
    }

    /** Always applicable: it is the fallback, so it must be consulted last. */
    @Override
    public boolean appliesTo(ProjectInputs inputs) {
        return true;
    }

    @Override
    public NativeImageInputPlan plan(ProjectInputs inputs) throws InputPlanningException {
        return planDerived(inputs);
    }

    /** Builds an argfile from the packaged artifact plus the resolved runtime classpath. */
    private NativeImageInputPlan planDerived(ProjectInputs inputs) throws InputPlanningException {
        Path artifact = inputs.projectArtifact();
        if (artifact == null || !Files.isRegularFile(artifact)) {
            throw new InputPlanningException(
                    "No packaged artifact found for this project. Run 'mvn package' before this goal, "
                            + "or use a Quarkus native-sources build (" + QuarkusNativeSourcesStrategy.SOURCES_COMMAND
                            + ").");
        }

        String mainClass = inputs.mainClass() != null && !inputs.mainClass().isBlank()
                ? inputs.mainClass().trim()
                : mainClassFromManifest(artifact).orElseThrow(() -> new InputPlanningException(
                        "Cannot determine the main class: " + artifact.getFileName()
                                + " has no Main-Class manifest entry. Configure <mainClass> in the "
                                + "plugin configuration."));

        String imageName = inputs.imageName() != null && !inputs.imageName().isBlank()
                ? inputs.imageName().trim()
                : inputs.finalName();

        List<StagedFile> files = new ArrayList<>();
        String artifactName = artifact.getFileName().toString();
        files.add(new StagedFile(artifactName, artifact));

        List<String> classpathEntries = new ArrayList<>();
        classpathEntries.add(artifactName);

        Set<String> usedNames = new HashSet<>();
        usedNames.add(artifactName);
        for (Path entry : inputs.runtimeClasspath()) {
            if (Files.isDirectory(entry)) {
                // The project's own classes directory is already covered by the packaged artifact;
                // any other directory entry would need packaging we should not silently invent.
                if (isProjectClassesDirectory(entry, inputs)) {
                    continue;
                }
                throw new InputPlanningException(
                        "Runtime classpath entry " + entry + " is a directory. Remote builds need "
                                + "packaged jars; build the dependency with 'mvn package' or install it.");
            }
            if (!Files.isRegularFile(entry)) {
                continue;
            }
            if (sameFile(entry, artifact)) {
                continue;
            }
            String libName = uniqueName(entry.getFileName().toString(), usedNames);
            String relativePath = StagingLayout.LIB_DIR_NAME + "/" + libName;
            files.add(new StagedFile(relativePath, entry));
            classpathEntries.add(relativePath);
        }

        String argsContent = renderArgs(classpathEntries, imageName, mainClass,
                inputs.extraBuildArgs());
        return NativeImageInputPlan.generated(files, argsContent, List.of(imageName));
    }

    /**
     * Renders a native-image argument file: one argument per line, all paths relative to the staging
     * root, which is also the process working directory.
     */
    private String renderArgs(List<String> classpathEntries, String imageName, String mainClass,
                              List<String> extraBuildArgs) {
        List<String> lines = new ArrayList<>();
        lines.add("-cp");
        lines.add(String.join(":", classpathEntries));
        lines.add("--no-fallback");
        lines.addAll(extraBuildArgs);
        lines.add("-o");
        lines.add(StagingLayout.OUTPUT_DIR_NAME + "/" + imageName);
        lines.add(mainClass);
        return String.join(System.lineSeparator(), lines) + System.lineSeparator();
    }

    private Optional<String> mainClassFromManifest(Path jar) throws InputPlanningException {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            Manifest manifest = jarFile.getManifest();
            if (manifest == null) {
                return Optional.empty();
            }
            String mainClass = manifest.getMainAttributes().getValue("Main-Class");
            return Optional.ofNullable(mainClass).filter(value -> !value.isBlank()).map(String::trim);
        } catch (IOException e) {
            throw new InputPlanningException("Failed to read the manifest of " + jar, e);
        }
    }

    private boolean isProjectClassesDirectory(Path entry, ProjectInputs inputs) {
        return entry.toAbsolutePath().normalize()
                .startsWith(inputs.targetDirectory().toAbsolutePath().normalize());
    }

    private boolean sameFile(Path left, Path right) {
        try {
            return Files.isSameFile(left, right);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to compare " + left + " and " + right, e);
        }
    }

    /** Keeps {@code lib/} names unique when two artifacts share a file name. */
    private String uniqueName(String preferredName, Set<String> usedNames) {
        if (usedNames.add(preferredName)) {
            return preferredName;
        }
        int suffix = 2;
        while (true) {
            int dot = preferredName.lastIndexOf('.');
            String candidate = dot < 0
                    ? preferredName + "-" + suffix
                    : preferredName.substring(0, dot) + "-" + suffix + preferredName.substring(dot);
            if (usedNames.add(candidate)) {
                return candidate;
            }
            suffix++;
        }
    }

    private static String toPosix(Path relativePath) {
        return relativePath.toString().replace('\\', '/');
    }
}
