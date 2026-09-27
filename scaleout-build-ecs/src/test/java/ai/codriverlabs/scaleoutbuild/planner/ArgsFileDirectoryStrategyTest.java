/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.planner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the relocation of a {@code native-maven-plugin} argfile.
 *
 * <p>The fixtures reproduce what Spring Boot 4.1.0 with {@code native-maven-plugin} 1.1.1 actually emitted
 * when this was verified against {@code spring-petclinic}: a randomized filename, an absolute {@code -cp}
 * mixing the project's classes directory with jars from {@code ~/.m2/repository}, an absolute {@code -o}, no
 * main-class argument (it arrives via {@code META-INF/native-image/.../native-image.properties}), and a
 * {@code -H:ConfigurationFileDirectories} listing the reachability-metadata cache.
 */
class ArgsFileDirectoryStrategyTest {

    private final ArgsFileDirectoryStrategy strategy = new ArgsFileDirectoryStrategy();

    /** The randomized name is the whole reason a fixed filename could not work. */
    @Test
    void findsRandomizedArgsFileName(@TempDir Path temp) throws Exception {
        Path project = petclinicLike(temp, "native-image-2514289111389238304.args");

        NativeImageInputPlan plan = strategy.plan(inputs(project));

        assertThat(plan.generatedArgsContent()).isPresent();
    }

    /**
     * The project's classes directory must be staged as a tree. It carries
     * {@code META-INF/native-image/}, which is where the main class and every reachability entry live —
     * dropping it would produce a binary missing both.
     */
    @Test
    void stagesClasspathDirectoryAsTreeSoMetadataSurvives(@TempDir Path temp) throws Exception {
        Path project = petclinicLike(temp, "native-image-1.args");

        NativeImageInputPlan plan = strategy.plan(inputs(project));

        assertThat(plan.files()).extracting(StagedFile::relativePath)
                .contains("classes/META-INF/native-image/org.springframework.samples/spring-petclinic/"
                                + "native-image.properties",
                        "classes/META-INF/native-image/com.zaxxer/HikariCP/7.0.2/reachability-metadata.json",
                        "classes/org/example/App.class");
    }

    /** Jars land in {@code lib/}, and the rewritten classpath references them by relative path. */
    @Test
    void rewritesClasspathToStagedRelativePaths(@TempDir Path temp) throws Exception {
        Path project = petclinicLike(temp, "native-image-1.args");

        NativeImageInputPlan plan = strategy.plan(inputs(project));

        assertThat(plan.files()).extracting(StagedFile::relativePath)
                .contains("lib/spring-core-7.0.8.jar", "lib/HikariCP-7.0.2.jar");
        assertThat(classpathLineOf(plan))
                .isEqualTo("classes:lib/spring-core-7.0.8.jar:lib/HikariCP-7.0.2.jar");
        assertThat(plan.generatedArgsContent().orElseThrow()).doesNotContain("/home/");
    }

    /** {@code -o} is rewritten into the staging output directory, and names the expected artifact. */
    @Test
    void rewritesOutputPathAndRecordsExpectedArtifact(@TempDir Path temp) throws Exception {
        Path project = petclinicLike(temp, "native-image-1.args");

        NativeImageInputPlan plan = strategy.plan(inputs(project));

        assertThat(lines(plan)).containsSequence("-o", "output/spring-petclinic");
        assertThat(plan.expectedArtifacts()).containsExactly("spring-petclinic");
    }

    /**
     * Staged, not dropped. Dropping it produced a petclinic binary that compiled and then failed with
     * {@code Invalid logger interface org.hibernate.validator...Log (implementation not found)}: the cache
     * held 356 reflection entries for hibernate-validator 7.0.4.Final including {@code Log_$logger}, while
     * the classpath copy held 12 for 9.1.0.Final including none.
     */
    @Test
    void stagesConfigurationFileDirectoriesAndRewritesTheFlag(@TempDir Path temp) throws Exception {
        Path project = petclinicLike(temp, "native-image-1.args");

        NativeImageInputPlan plan = strategy.plan(inputs(project));

        assertThat(lines(plan)).contains("-H:ConfigurationFileDirectories=config/7.0.2");
        assertThat(plan.files()).extracting(StagedFile::relativePath)
                .contains("config/7.0.2/reachability-metadata.json");
        assertThat(plan.generatedArgsContent().orElseThrow()).doesNotContain("/home/");
    }

    /** A stale argfile can outlive a clean; native-image tolerates a missing config dir, so skip it. */
    @Test
    void skipsConfigurationDirectoriesThatNoLongerExist(@TempDir Path temp) throws Exception {
        Path project = temp.resolve("target");
        Files.createDirectories(project);
        jar(project.resolve("lib/only.jar"));
        writeArgs(project.resolve("native-image-8.args"), List.of(
                "-cp", project.resolve("lib/only.jar").toString(),
                "-o", project.resolve("app").toString(),
                "-H:ConfigurationFileDirectories=" + temp.resolve("cleaned-away")));

        NativeImageInputPlan plan = strategy.plan(inputs(project));

        assertThat(plan.generatedArgsContent().orElseThrow())
                .doesNotContain("-H:ConfigurationFileDirectories");
    }

    /** Arguments the strategy does not understand must survive untouched. */
    @Test
    void preservesUnrecognisedArgumentsVerbatim(@TempDir Path temp) throws Exception {
        Path project = petclinicLike(temp, "native-image-1.args");
        appendArgs(project, "--no-fallback", "-march=compatibility", "--enable-monitoring=jfr");

        NativeImageInputPlan plan = strategy.plan(inputs(project));

        assertThat(lines(plan))
                .contains("--no-fallback", "-march=compatibility", "--enable-monitoring=jfr");
    }

    /** {@code -H:Path} would otherwise write outside the directory the agent collects from. */
    @Test
    void redirectsOutputDirectoryFlagIntoStagingOutput(@TempDir Path temp) throws Exception {
        Path project = petclinicLike(temp, "native-image-1.args");
        appendArgs(project, "-H:Path=/home/ubuntu/project/target");

        NativeImageInputPlan plan = strategy.plan(inputs(project));

        assertThat(lines(plan)).contains("-H:Path=output").doesNotContain(
                "-H:Path=/home/ubuntu/project/target");
    }

    /** {@code -H:Name} is the other way to name the image, and must be honoured as an expected artifact. */
    @Test
    void acceptsImageNameFromHName(@TempDir Path temp) throws Exception {
        Path project = temp.resolve("target");
        Files.createDirectories(project);
        jar(project.resolve("lib/only.jar"));
        writeArgs(project.resolve("native-image-9.args"), List.of(
                "-cp", project.resolve("lib/only.jar").toString(),
                "-H:Name=custom-binary"));

        NativeImageInputPlan plan = strategy.plan(inputs(project));

        assertThat(plan.expectedArtifacts()).containsExactly("custom-binary");
    }

    /**
     * Stale argfiles accumulate precisely because the name is randomized, so guessing which is current would
     * silently build the wrong thing.
     */
    @Test
    void refusesToChooseBetweenMultipleArgsFiles(@TempDir Path temp) throws Exception {
        Path project = petclinicLike(temp, "native-image-111.args");
        Files.copy(project.resolve("native-image-111.args"), project.resolve("native-image-222.args"));

        assertThatThrownBy(() -> strategy.plan(inputs(project)))
                .isInstanceOf(InputPlanningException.class)
                .hasMessageContaining("contains 2 *.args files")
                .hasMessageContaining("native-image-111.args")
                .hasMessageContaining("native-image-222.args");
    }

    @Test
    void reportsMissingArgsFileWithTheGoalThatCreatesIt(@TempDir Path temp) throws Exception {
        Path project = temp.resolve("target");
        Files.createDirectories(project);

        assertThatThrownBy(() -> strategy.plan(inputs(project)))
                .isInstanceOf(InputPlanningException.class)
                .hasMessageContaining("no *.args file")
                .hasMessageContaining("native:write-args-file");
    }

    /**
     * A classpath entry that has been cleaned away would otherwise be dropped silently, yielding a build
     * missing a dependency — which fails confusingly, late, and remotely.
     */
    @Test
    void failsLoudlyOnAClasspathEntryThatNoLongerExists(@TempDir Path temp) throws Exception {
        Path project = temp.resolve("target");
        Files.createDirectories(project);
        writeArgs(project.resolve("native-image-3.args"), List.of(
                "-cp", temp.resolve("gone/missing.jar").toString(),
                "-o", project.resolve("app").toString()));

        assertThatThrownBy(() -> strategy.plan(inputs(project)))
                .isInstanceOf(InputPlanningException.class)
                .hasMessageContaining("missing.jar")
                .hasMessageContaining("does not exist");
    }

    /** Without an image name the agent has nothing to collect, so this must not pass silently. */
    @Test
    void failsWhenTheArgfileNamesNoOutputImage(@TempDir Path temp) throws Exception {
        Path project = temp.resolve("target");
        Files.createDirectories(project);
        jar(project.resolve("lib/only.jar"));
        writeArgs(project.resolve("native-image-4.args"), List.of(
                "-cp", project.resolve("lib/only.jar").toString(), "--no-fallback"));

        assertThatThrownBy(() -> strategy.plan(inputs(project)))
                .isInstanceOf(InputPlanningException.class)
                .hasMessageContaining("names no output image");
    }

    @Test
    void failsOnAnArgfileTruncatedAfterAFlag(@TempDir Path temp) throws Exception {
        Path project = temp.resolve("target");
        Files.createDirectories(project);
        writeArgs(project.resolve("native-image-5.args"), List.of("-cp"));

        assertThatThrownBy(() -> strategy.plan(inputs(project)))
                .isInstanceOf(InputPlanningException.class)
                .hasMessageContaining("ends with '-cp'");
    }

    /** Two dependencies sharing a file name must not overwrite one another in {@code lib/}. */
    @Test
    void disambiguatesJarsThatShareAFileName(@TempDir Path temp) throws Exception {
        Path project = temp.resolve("target");
        Files.createDirectories(project);
        jar(temp.resolve("a/shared.jar"));
        jar(temp.resolve("b/shared.jar"));
        writeArgs(project.resolve("native-image-6.args"), List.of(
                "-cp", temp.resolve("a/shared.jar") + ":" + temp.resolve("b/shared.jar"),
                "-o", project.resolve("app").toString()));

        NativeImageInputPlan plan = strategy.plan(inputs(project));

        assertThat(plan.files()).extracting(StagedFile::relativePath)
                .contains("lib/shared.jar", "lib/shared-2.jar");
        assertThat(classpathLineOf(plan)).isEqualTo("lib/shared.jar:lib/shared-2.jar");
    }

    /** {@code -classpath} and {@code --class-path} are accepted spellings of the same flag. */
    @Test
    void acceptsAlternativeClasspathSpellings(@TempDir Path temp) throws Exception {
        for (String flag : List.of("-classpath", "--class-path")) {
            Path project = Files.createDirectories(temp.resolve(flag.replace("-", "x") + "/target"));
            jar(project.resolve("lib/only.jar"));
            writeArgs(project.resolve("native-image-7.args"), List.of(
                    flag, project.resolve("lib/only.jar").toString(),
                    "-o", project.resolve("app").toString()));

            NativeImageInputPlan plan = strategy.plan(inputs(project));

            assertThat(lines(plan)).containsSequence(flag, "lib/only.jar");
        }
    }

    @Test
    void doesNotApplyWhenNoDirectoryIsConfigured(@TempDir Path temp) {
        assertThat(strategy.appliesTo(inputs(null))).isFalse();
        assertThat(strategy.appliesTo(inputs(temp.resolve("absent")))).isFalse();
    }

    // ---------------------------------------------------------------- fixtures

    /**
     * Builds a project whose argfile mirrors the shape measured on {@code spring-petclinic}: classes
     * directory plus jars on an absolute classpath, absolute {@code -o}, no main class, and a
     * {@code -H:ConfigurationFileDirectories} naming the metadata cache.
     */
    private Path petclinicLike(Path temp, String argsFileName) throws IOException {
        Path project = temp.resolve("target");
        Path classes = project.resolve("classes");
        write(classes.resolve("org/example/App.class"), "cafebabe");
        write(classes.resolve("META-INF/native-image/org.springframework.samples/spring-petclinic/"
                + "native-image.properties"), "Args = -H:Class=org.example.App");
        write(classes.resolve("META-INF/native-image/com.zaxxer/HikariCP/7.0.2/"
                + "reachability-metadata.json"), "{}");
        Path springCore = temp.resolve("m2/org/springframework/spring-core-7.0.8.jar");
        Path hikari = temp.resolve("m2/com/zaxxer/HikariCP-7.0.2.jar");
        jar(springCore);
        jar(hikari);
        Path cache = project.resolve("graalvm-reachability-metadata/53215db5/com.zaxxer/HikariCP/7.0.2");
        write(cache.resolve("reachability-metadata.json"), "{}");

        writeArgs(project.resolve(argsFileName), List.of(
                "-cp", String.join(":", classes.toString(), springCore.toString(), hikari.toString()),
                "--no-fallback",
                "-o", project.resolve("spring-petclinic").toString(),
                "-H:ConfigurationFileDirectories=" + cache));
        return project;
    }

    private void appendArgs(Path project, String... extra) throws IOException {
        Path argsFile;
        try (var entries = Files.list(project)) {
            argsFile = entries.filter(p -> p.getFileName().toString().endsWith(".args")).findFirst()
                    .orElseThrow();
        }
        List<String> all = new java.util.ArrayList<>(Files.readAllLines(argsFile));
        all.addAll(List.of(extra));
        writeArgs(argsFile, all);
    }

    private void writeArgs(Path file, List<String> lines) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
    }

    private void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private void jar(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        try (var out = new java.util.jar.JarOutputStream(Files.newOutputStream(file))) {
            out.putNextEntry(new java.util.zip.ZipEntry("marker"));
            out.closeEntry();
        }
    }

    private ProjectInputs inputs(Path argsFileDirectory) {
        return new ProjectInputs(Path.of("target"), "app", null, List.of(), null, null, List.of(),
                argsFileDirectory);
    }

    private List<String> lines(NativeImageInputPlan plan) {
        return List.of(plan.generatedArgsContent().orElseThrow().split("\\R"));
    }

    private String classpathLineOf(NativeImageInputPlan plan) {
        List<String> lines = lines(plan);
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).equals("-cp") || lines.get(i).equals("-classpath")
                    || lines.get(i).equals("--class-path")) {
                return lines.get(i + 1);
            }
        }
        throw new AssertionError("No classpath flag in:\n" + String.join("\n", lines));
    }
}
