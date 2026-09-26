/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.planner;

import ai.codriverlabs.scaleoutbuild.build.StagingLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Quarkus: stages the directory Quarkus writes its native-image sources into and reuses its argument file.
 *
 * <p>Verified against Quarkus 3.39.4 on a 233-jar operator application.
 *
 * <p>Quarkus augmentation generates a complete {@code native-image} command line — a Quarkus feature
 * registration, reflection and resource configuration, {@code --exclude-config} regexes for Netty, around
 * thirty {@code -J-D} system properties, and the output name. None of that is recoverable from a
 * classpath, so the derived strategy would produce a binary that either fails to build or misbehaves at
 * run time. Reusing the generated file is the only correct approach.
 *
 * <p>Generate the sources with:
 *
 * <pre>
 *   mvn package -Dquarkus.native.enabled=true -Dquarkus.native.sources-only=true
 * </pre>
 */
public final class QuarkusNativeSourcesStrategy implements InputPlanStrategy {

    /*
     * Current Quarkus rejects the older packaging property outright:
     *   SRCFG00049: Cannot convert native-sources to enum ... allowed values: fast-jar, legacy-jar,
     *   uber-jar, aot-jar, mutable-jar
     * so advice has to be version-aware or it sends people down a dead end.
     */
    static final String SOURCES_COMMAND =
            "mvn package -Dquarkus.native.enabled=true -Dquarkus.native.sources-only=true";
    static final String SOURCES_COMMAND_LEGACY =
            "mvn package -Dquarkus.package.jar.type=native-sources   (older Quarkus only)";

    @Override
    public String name() {
        return "Quarkus";
    }

    @Override
    public boolean appliesTo(ProjectInputs inputs) {
        return inputs.quarkusNativeSourcesCandidates().stream().anyMatch(Files::isDirectory);
    }

    @Override
    public NativeImageInputPlan plan(ProjectInputs inputs) throws InputPlanningException {
        Path sourcesDirectory = inputs.quarkusNativeSourcesDirectory();
        Path argsFile = sourcesDirectory.resolve(StagingLayout.DEFAULT_ARGS_FILE_NAME);
        if (!Files.isRegularFile(argsFile)) {
            throw new InputPlanningException(
                    "Found " + sourcesDirectory.getFileName() + " but no "
                            + StagingLayout.DEFAULT_ARGS_FILE_NAME + " inside it. An ordinary native build "
                            + "leaves that directory behind without the arguments. Re-run so Quarkus emits "
                            + "them:\n    " + SOURCES_COMMAND + "\nor, on older Quarkus:\n    "
                            + SOURCES_COMMAND_LEGACY);
        }

        List<StagedFile> files = new ArrayList<>();
        try (Stream<Path> tree = Files.walk(sourcesDirectory)) {
            for (Path path : tree.filter(Files::isRegularFile).toList()) {
                files.add(new StagedFile(toPosix(sourcesDirectory.relativize(path)), path));
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

    private static String toPosix(Path relativePath) {
        return relativePath.toString().replace(Path.of("").getFileSystem().getSeparator(), "/");
    }
}
