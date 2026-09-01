/*
 * Copyright © 2026 Plasticity.Cloud and CoDriverLabs. All rights reserved.
 */
package cloud.plasticity.jobrunr.maven.planner;

import cloud.plasticity.jobrunr.build.StagingLayout;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/**
 * Decides what to ship to a remote builder, and how to invoke {@code native-image} there.
 *
 * <p>Three cases, in priority order:
 *
 * <ol>
 *   <li><b>Quarkus argfile present</b> — stage the generated sources directory and pass its
 *       {@code native-image.args} through verbatim.</li>
 *   <li><b>Sources directory present, argfile absent</b> — fail with the exact command to run. This
 *       case is common and easy to misread: an ordinary native build leaves a
 *       {@code *-native-image-source-jar/} directory behind <em>without</em> an argfile, and Quarkus
 *       extensions inject enough {@code -H:} flags that reconstructing the arguments would be
 *       guesswork.</li>
 *   <li><b>Neither</b> — derive an argfile from the packaged artifact and the runtime classpath.</li>
 * </ol>
 */
public final class NativeImageInputPlanner {

    private static final String QUARKUS_NATIVE_SOURCES_COMMAND =
            "mvn package -Dquarkus.package.jar.type=native-sources";

    /** Plans staging for the given project, choosing the input mode automatically. */
    public NativeImageInputPlan plan(ProjectInputs inputs) throws InputPlanningException {
        Path sourcesDirectory = inputs.quarkusNativeSourcesDirectory();
        if (Files.isDirectory(sourcesDirectory)) {
            Path argsFile = sourcesDirectory.resolve(StagingLayout.DEFAULT_ARGS_FILE_NAME);
            if (Files.isRegularFile(argsFile)) {
                return planFromNativeSources(sourcesDirectory);
            }
            throw new InputPlanningException(
                    "Found " + sourcesDirectory.getFileName() + " but no "
                            + StagingLayout.DEFAULT_ARGS_FILE_NAME + " inside it. That directory is a "
                            + "byproduct of an ordinary native build and does not contain the "
                            + "native-image arguments. Re-run the build with the native-sources "
                            + "packaging type to generate them:\n    " + QUARKUS_NATIVE_SOURCES_COMMAND
                            + "\n(for Quarkus before 3.9 use -Dquarkus.package.type=native-sources), "
                            + "or configure <inputMode>derived</inputMode> to have the plugin build the "
                            + "argument file from the runtime classpath instead.");
        }
        return planDerived(inputs);
    }

    /** Stages the Quarkus sources directory as-is and reuses its argfile. */
    private NativeImageInputPlan planFromNativeSources(Path sourcesDirectory)
            throws InputPlanningException {
        List<StagedFile> files = new ArrayList<>();
        try (Stream<Path> tree = Files.walk(sourcesDirectory)) {
            for (Path path : tree.filter(Files::isRegularFile).toList()) {
                String relativePath = toPosix(sourcesDirectory.relativize(path));
                files.add(new StagedFile(relativePath, path));
            }
        } catch (IOException e) {
            throw new InputPlanningException("Failed to scan " + sourcesDirectory, e);
        }
        if (files.isEmpty()) {
            throw new InputPlanningException("No files found under " + sourcesDirectory);
        }
        // The argfile names its own output, so artifacts are discovered by scanning after the build.
        return NativeImageInputPlan.passThrough(files, StagingLayout.DEFAULT_ARGS_FILE_NAME, List.of());
    }

    /** Builds an argfile from the packaged artifact plus the resolved runtime classpath. */
    private NativeImageInputPlan planDerived(ProjectInputs inputs) throws InputPlanningException {
        Path artifact = inputs.projectArtifact();
        if (artifact == null || !Files.isRegularFile(artifact)) {
            throw new InputPlanningException(
                    "No packaged artifact found for this project. Run 'mvn package' before this goal, "
                            + "or use a Quarkus native-sources build (" + QUARKUS_NATIVE_SOURCES_COMMAND
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
