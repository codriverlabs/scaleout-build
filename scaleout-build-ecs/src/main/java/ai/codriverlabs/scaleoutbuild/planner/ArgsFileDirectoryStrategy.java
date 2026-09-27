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
 * Any framework whose build emits a {@code native-image} argument file via GraalVM's
 * {@code native-maven-plugin}.
 *
 * <p>That covers <b>Spring Boot AOT</b>, <b>Helidon</b>, and plain GraalVM projects that use the plugin,
 * because all of them delegate the native build to it rather than inventing their own. One strategy
 * therefore handles three frameworks: the thing worth detecting is the argument file, not the framework
 * that caused it to exist.
 *
 * <p>Generate it with the plugin's own goal, then point this strategy at the result:
 *
 * <pre>
 *   mvn native:write-args-file
 *   mvn package -Dscaleout-build.argsFileDirectory=target/&lt;wherever it wrote&gt;
 * </pre>
 *
 * <p><b>The directory must be configured explicitly; there is no default.</b> That is a deliberate
 * refusal to guess. {@code write-args-file} takes its location from the
 * {@code graalvm.native-image.args-file} property, and this strategy has not been verified end to end
 * against a Spring Boot or Helidon build. A hardcoded wrong path would not fail loudly — it would fail
 * {@link #appliesTo}, fall through to {@link DerivedClasspathStrategy}, and produce a binary that builds
 * successfully and then misbehaves at run time, because an AOT-processed application needs the generated
 * reflection and resource configuration that a derived argfile omits. Requiring the path makes the
 * unverified part explicit rather than silently wrong.
 *
 * <p>Everything alongside the argument file is staged with it, on the assumption the arguments reference it
 * by relative path — which is how Quarkus's equivalent directory works.
 */
public final class ArgsFileDirectoryStrategy implements InputPlanStrategy {

    @Override
    public String name() {
        return "native-maven-plugin argfile (Spring Boot AOT, Helidon, plain GraalVM)";
    }

    @Override
    public boolean appliesTo(ProjectInputs inputs) {
        Path directory = inputs.argsFileDirectory();
        return directory != null && Files.isDirectory(directory);
    }

    @Override
    public NativeImageInputPlan plan(ProjectInputs inputs) throws InputPlanningException {
        Path directory = inputs.argsFileDirectory();
        Path argsFile = directory.resolve(StagingLayout.DEFAULT_ARGS_FILE_NAME);
        if (!Files.isRegularFile(argsFile)) {
            throw new InputPlanningException(
                    "Configured argsFileDirectory " + directory + " has no "
                            + StagingLayout.DEFAULT_ARGS_FILE_NAME + ". Generate one with "
                            + "'mvn native:write-args-file' and make sure it is named "
                            + StagingLayout.DEFAULT_ARGS_FILE_NAME + " in that directory, or leave "
                            + "argsFileDirectory unset to derive the arguments from the runtime "
                            + "classpath instead.");
        }

        List<StagedFile> files = new ArrayList<>();
        try (Stream<Path> tree = Files.walk(directory)) {
            for (Path path : tree.filter(Files::isRegularFile).toList()) {
                files.add(new StagedFile(toPosix(directory.relativize(path)), path));
            }
        } catch (IOException e) {
            throw new InputPlanningException("Failed to scan " + directory, e);
        }
        return NativeImageInputPlan.passThrough(files, StagingLayout.DEFAULT_ARGS_FILE_NAME, List.of());
    }

    private static String toPosix(Path relativePath) {
        return relativePath.toString().replace(Path.of("").getFileSystem().getSeparator(), "/");
    }
}
