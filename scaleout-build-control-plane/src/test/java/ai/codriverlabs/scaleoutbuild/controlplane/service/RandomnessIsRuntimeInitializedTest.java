/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.security.SecureRandom;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Guards the native-image constraint on randomness sources.
 *
 * <p>GraalVM runs static initializers at image build time, so a {@code static} field holding a
 * {@link Random} or {@link SecureRandom} is constructed during the build and baked into the image heap
 * with its seed already cached. {@code native-image} refuses to build in that case, and it is right to:
 * every Lambda instance started from such an image would emit the same sequence of values.
 *
 * <p>Here that sequence is the build id's entropy suffix, and the build id is the DynamoDB table's
 * partition key — so a repeated suffix lets two owners' builds collide on one item. The per-owner GSI
 * does not protect the base table.
 *
 * <p>This is a reflection test, which is unusual, and the justification is the feedback loop. Without
 * it the mistake surfaces only in a native build: several minutes in, as
 * {@code UnsupportedFeatureException: Detected an instance of Random/SplittableRandom class in the image
 * heap}, which names the JDK class rather than the field that caused it. With it, the mistake fails in
 * {@code mvn verify} in milliseconds with an explanation. CI does not build the native image on every
 * push, so without this the gap could be reintroduced and not noticed until a release.
 */
class RandomnessIsRuntimeInitializedTest {

    /**
     * Every randomness source in this service must be an instance field. Checked across the classes that
     * hold one rather than named individually, so a new static one is caught wherever it appears.
     */
    @Test
    void noStaticRandomnessSourcesInServiceClasses() {
        for (Class<?> type : new Class<?>[] {BuildService.class, StagingService.class,
                ResourcePolicy.class}) {
            for (Field field : type.getDeclaredFields()) {
                if (Random.class.isAssignableFrom(field.getType())) {
                    assertThat(Modifier.isStatic(field.getModifiers()))
                            .as("%s.%s is a static randomness source. GraalVM would seed it at image "
                                    + "build time and bake the seed into the image heap, so every "
                                    + "instance would repeat the same sequence. Make it an instance "
                                    + "field; this is a CDI bean, so it will be seeded at runtime.",
                                    type.getSimpleName(), field.getName())
                            .isFalse();
                }
            }
        }
    }

    /**
     * And the field is actually present where it is needed, so the test above cannot pass merely because
     * someone deleted the field and reached for a static utility instead.
     */
    @Test
    void buildServiceHoldsAnInstanceRandom() throws NoSuchFieldException {
        Field field = BuildService.class.getDeclaredField("random");

        assertThat(Modifier.isStatic(field.getModifiers())).isFalse();
        assertThat(SecureRandom.class).isAssignableFrom(field.getType());
    }
}
