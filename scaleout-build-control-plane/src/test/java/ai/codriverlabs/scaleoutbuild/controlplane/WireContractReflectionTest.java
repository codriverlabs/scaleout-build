/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import ai.codriverlabs.scaleoutbuild.controlplane.api.ErrorResponse;
import ai.codriverlabs.scaleoutbuild.ecs.MicroVmCellLauncher;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Asserts that every wire type is registered for reflection.
 *
 * <p>A missing registration is invisible in JVM mode and in every other test here: it surfaces only as an
 * HTTP 500 from a native image, attributed to Jackson rather than to the API change that caused it. That
 * is slow and misleading feedback, so the list is checked mechanically rather than by review.
 *
 * <p>The failure this prevents has already happened once. The first native deployment returned 500 for
 * every {@code /builds} response while health checks reported UP, because {@code BuildApi} methods return
 * a raw {@code Response} and Quarkus therefore cannot infer the entity type to register.
 *
 * <p>The type list is read from the compiled API artifact rather than hardcoded, so adding a record to the
 * wire contract fails this test until it is registered.
 */
class WireContractReflectionTest {

    private static final String API_PACKAGE_PATH =
            "ai/codriverlabs/scaleoutbuild/controlplane/api";

    /**
     * Types that are never serialized as a response body, so need no reflection metadata.
     *
     * <p>{@code BuildApi} is the JAX-RS interface itself. Keep this set as small as the truth allows: every
     * entry is an assertion that something cannot appear in a payload.
     */
    private static final Set<String> NOT_SERIALIZED_AS_ENTITIES = Set.of(
            "BuildApi",   // JAX-RS interface, never serialized
            "AdminApi");  // container class for request/response records, never serialized directly

    @Test
    void everyApiTypeIsRegisteredForReflection() {
        Set<String> registered = Set.of(WireContractReflectionConfig.class
                        .getAnnotation(RegisterForReflection.class).targets())
                .stream()
                .map(Class::getSimpleName)
                .collect(Collectors.toSet());

        List<String> apiTypes = apiTypeNames();

        assertThat(apiTypes)
                .as("sanity check: the API artifact should have been found on the test classpath, "
                        + "otherwise this test silently asserts nothing")
                .isNotEmpty();

        List<String> missing = apiTypes.stream()
                .filter(name -> !NOT_SERIALIZED_AS_ENTITIES.contains(name))
                .filter(name -> !registered.contains(name))
                .toList();

        assertThat(missing)
                .as("Not listed in WireContractReflectionConfig. In a native image Jackson cannot "
                        + "serialize these and the endpoint returns HTTP 500, while JVM mode keeps "
                        + "working -- so nothing else here would catch it. Add them to the "
                        + "@RegisterForReflection targets, or to NOT_SERIALIZED_AS_ENTITIES if they "
                        + "genuinely never appear in a payload.")
                .isEmpty();
    }

    /**
     * Asserts {@code MicrovmReflectionConfig} still registers {@code MicroVmCellLauncher
     * .StatusReport} -- the one confirmed-in-production native-image reflection gap this project
     * has actually hit (see that config class's own javadoc for the real failure: a valid
     * {@code status.json} the control plane could never deserialize, consistently, for the whole
     * MicroVM build). Deliberately a narrower, explicit check rather than trying to mechanically
     * discover every plain-{@code ObjectMapper} deserialization target across every dependency
     * module the way {@link #everyApiTypeIsRegisteredForReflection} does for wire DTOs -- those
     * are enumerable from one well-known package; types read by ad hoc {@code ObjectMapper} calls
     * scattered across modules are not, so this at least pins the one type already proven to bite.
     */
    @Test
    void microVmStatusReportIsRegisteredForReflection() {
        Set<String> registered = Set.of(MicrovmReflectionConfig.class
                        .getAnnotation(RegisterForReflection.class).targets())
                .stream()
                .map(Class::getName)
                .collect(Collectors.toSet());

        assertThat(registered)
                .as("MicroVmCellLauncher.StatusReport must stay registered in "
                        + "MicrovmReflectionConfig -- removing it silently reintroduces the "
                        + "exact native-image deserialization failure found in production: a "
                        + "valid status.json that readStatusReport() can never parse, so the "
                        + "control plane never learns a MicroVM cell actually succeeded.")
                .contains(MicroVmCellLauncher.StatusReport.class.getName());
    }

    /** Top-level type names in the API package, read from wherever the artifact actually is. */
    private static List<String> apiTypeNames() {
        try {
            Path source = Path.of(ErrorResponse.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());

            if (Files.isDirectory(source)) {
                try (Stream<Path> files = Files.list(source.resolve(API_PACKAGE_PATH))) {
                    return files.map(p -> p.getFileName().toString())
                            .filter(WireContractReflectionTest::isTopLevelClassFile)
                            .map(WireContractReflectionTest::toSimpleName)
                            .sorted()
                            .toList();
                }
            }

            // Reactor build: the API module is a jar on the test classpath.
            List<String> names = new ArrayList<>();
            try (JarFile jar = new JarFile(source.toFile())) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    if (!name.startsWith(API_PACKAGE_PATH + "/")) {
                        continue;
                    }
                    String simple = name.substring(API_PACKAGE_PATH.length() + 1);
                    if (!simple.contains("/") && isTopLevelClassFile(simple)) {
                        names.add(toSimpleName(simple));
                    }
                }
            }
            return names.stream().sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (URISyntaxException e) {
            throw new IllegalStateException("could not locate the API artifact", e);
        }
    }

    /** Excludes nested and synthetic classes, which are covered by their enclosing type. */
    private static boolean isTopLevelClassFile(String fileName) {
        return fileName.endsWith(".class") && !fileName.contains("$");
    }

    private static String toSimpleName(String fileName) {
        return fileName.substring(0, fileName.length() - ".class".length());
    }
}
