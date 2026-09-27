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
 * <p><b>That assumption has been measured and is wrong for {@code write-args-file} — but the consequence is
 * narrower than it first appears.</b> Verified against Spring Boot 4.1.0 with {@code native-maven-plugin}
 * 1.1.1: the goal writes {@code target/native-image-<random long>.args}, a randomized name this strategy
 * will not find, and its arguments are <em>absolute host paths</em> — 107 references in one small
 * application, including a {@code -cp} into {@code ~/.m2/repository}.
 *
 * <p><b>Nothing about Spring Boot's output is hostile to building elsewhere.</b> {@code write-args-file} is
 * designed for local invocation on the machine that produced it, where absolute paths are correct;
 * generalising from Quarkus's deliberately relocatable {@code native-sources} was this strategy's error, not
 * a defect in Spring or GraalVM. Relocating an argfile is mechanical.
 *
 * <p>It is also mostly already solved here. GraalVM auto-discovers configuration from
 * {@code META-INF/native-image/} anywhere on the classpath, and Spring's AOT processing populates
 * {@code target/classes/META-INF/native-image/} — measured at 58 {@code reachability-metadata.json} files,
 * 1.9 MB, covering the same libraries as the 57 {@code -H:ConfigurationFileDirectories} entries in the
 * argfile. So the AOT-generated configuration travels with the classpath we already stage, and those
 * absolute directory references look redundant rather than load-bearing.
 *
 * <p>What remains is therefore small: glob for {@code native-image-*.args}, stage the {@code -cp} entries
 * through the existing per-blob content-addressed store (which {@link DerivedClasspathStrategy} already
 * does), rewrite {@code -cp} and {@code -o} to container paths, and drop or rewrite
 * {@code -H:ConfigurationFileDirectories}. <b>Not yet implemented, and the redundancy claim needs a
 * byte-comparison against a locally built binary before it is relied upon</b> — scoped in
 * {@code docs/design/control-plane/matrix-axes-and-image-strategy.md}.
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
