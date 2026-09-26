/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.planner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins which strategy wins for each shape of project.
 *
 * <p>Worth testing because the failure mode is silent. If a framework project falls through to
 * {@link DerivedClasspathStrategy}, the build succeeds and produces a binary that omits the AOT-generated
 * reflection and feature configuration, so it fails at run time instead — long after anyone would connect
 * it to input planning. Nothing else in the suite would notice.
 */
class NativeImageInputPlannerStrategyTest {

    private final NativeImageInputPlanner planner = new NativeImageInputPlanner();

    private ProjectInputs inputs(Path target, String finalName, Path argsFileDirectory) {
        return new ProjectInputs(target, finalName, target.resolve(finalName + ".jar"), List.of(), null,
                null, List.of(), argsFileDirectory);
    }

    /** Current Quarkus writes {@code target/native-sources}. Verified against 3.39.4. */
    @Test
    void quarkusModernLayoutWins(@TempDir Path target) throws IOException, InputPlanningException {
        Path sources = Files.createDirectories(target.resolve("native-sources"));
        Files.writeString(sources.resolve("native-image.args"), "-o app -jar app-runner.jar");
        Files.writeString(sources.resolve("app-runner.jar"), "jar");

        NativeImageInputPlan plan = planner.plan(inputs(target, "app", null));

        assertThat(planner.selectedStrategyName(inputs(target, "app", null))).isEqualTo("Quarkus");
        assertThat(plan.mode()).isEqualTo(InputMode.QUARKUS_NATIVE_SOURCES);
        assertThat(plan.files()).hasSize(2);
    }

    /**
     * Older Quarkus wrote {@code target/<finalName>-native-image-source-jar}. Kept because the plugin
     * previously probed only this name, which is why it silently fell through to derived on every modern
     * Quarkus project.
     */
    @Test
    void quarkusLegacyLayoutStillWins(@TempDir Path target) throws IOException {
        Path sources = Files.createDirectories(target.resolve("app-native-image-source-jar"));
        Files.writeString(sources.resolve("native-image.args"), "-o app");

        assertThat(planner.selectedStrategyName(inputs(target, "app", null))).isEqualTo("Quarkus");
    }

    /** A Quarkus directory without the argfile is the byproduct of an ordinary native build, not sources. */
    @Test
    void quarkusDirectoryWithoutArgfileFailsWithActionableAdvice(@TempDir Path target) throws IOException {
        Files.createDirectories(target.resolve("native-sources"));

        assertThatThrownBy(() -> planner.plan(inputs(target, "app", null)))
                .isInstanceOf(InputPlanningException.class)
                .hasMessageContaining("quarkus.native.sources-only=true");
    }

    /**
     * The generic argfile strategy covers Spring Boot AOT, Helidon and plain GraalVM, since all build
     * through native-maven-plugin.
     */
    @Test
    void configuredArgsFileDirectoryWins(@TempDir Path target, @TempDir Path argsDir)
            throws IOException, InputPlanningException {
        Files.writeString(argsDir.resolve("native-image.args"), "-o app -jar app.jar");

        ProjectInputs in = inputs(target, "app", argsDir);

        assertThat(planner.selectedStrategyName(in)).contains("native-maven-plugin");
        assertThat(planner.plan(in).mode()).isEqualTo(InputMode.QUARKUS_NATIVE_SOURCES);
    }

    /** Quarkus is consulted first, so its own layout wins even if an argfile directory is also set. */
    @Test
    void quarkusTakesPrecedenceOverAConfiguredArgsFileDirectory(@TempDir Path target,
                                                                @TempDir Path argsDir) throws IOException {
        Path sources = Files.createDirectories(target.resolve("native-sources"));
        Files.writeString(sources.resolve("native-image.args"), "-o quarkus-app");
        Files.writeString(argsDir.resolve("native-image.args"), "-o other-app");

        assertThat(planner.selectedStrategyName(inputs(target, "app", argsDir))).isEqualTo("Quarkus");
    }

    /** A configured directory that does not exist must not silently select the generic strategy. */
    @Test
    void missingArgsFileDirectoryFallsThroughToDerived(@TempDir Path target) {
        ProjectInputs in = inputs(target, "app", target.resolve("does-not-exist"));

        assertThat(planner.selectedStrategyName(in)).contains("derived");
    }

    /** With no framework output at all, the derived strategy is the fallback — and it must be last. */
    @Test
    void plainProjectFallsThroughToDerived(@TempDir Path target) {
        assertThat(planner.selectedStrategyName(inputs(target, "app", null))).contains("derived");
    }

    /**
     * Order is load-bearing: the derived strategy always applies, so putting it anywhere but last makes
     * every framework strategy unreachable. Asserted on the real default list rather than trusted.
     */
    @Test
    void derivedStrategyIsConsultedLast(@TempDir Path target) throws IOException {
        Path sources = Files.createDirectories(target.resolve("native-sources"));
        Files.writeString(sources.resolve("native-image.args"), "-o app");

        // If derived were ordered first it would win here, since it applies unconditionally.
        assertThat(planner.selectedStrategyName(inputs(target, "app", null)))
                .as("a project with framework output must not select the unconditional fallback")
                .doesNotContain("derived");
    }

    @Test
    void aStrategyListWithoutAFallbackFailsClearly(@TempDir Path target) {
        NativeImageInputPlanner restricted =
                new NativeImageInputPlanner(List.of(new QuarkusNativeSourcesStrategy()));

        assertThatThrownBy(() -> restricted.plan(inputs(target, "app", null)))
                .isInstanceOf(InputPlanningException.class)
                .hasMessageContaining("No input strategy recognised");
    }
}
