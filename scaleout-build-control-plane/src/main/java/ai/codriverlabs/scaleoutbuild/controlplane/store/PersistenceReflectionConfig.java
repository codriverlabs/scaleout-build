/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.controlplane.store;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Registers the DynamoDB persistence types for reflection in the native image.
 *
 * <p>Separate from {@code WireContractReflectionConfig} because the reason differs. Wire DTOs are missed
 * because {@code BuildApi} returns a raw {@code Response}, so Quarkus cannot infer the entity type. These
 * are missed because {@code BuildItems} serializes them by hand through an {@code ObjectMapper} into the
 * DynamoDB {@code document} attribute — an explicit mapper call gives Quarkus no signal at all, whatever
 * the method signatures look like.
 *
 * <p>Nested types are listed individually: registering an enclosing class does not cover them, which is
 * how {@code BuildRecord.CellRecord} and then {@code BuildItems.Document} each failed in turn, one native
 * deployment apart.
 *
 * <p>Lives in this package rather than alongside the wire config because {@code BuildItems} and its
 * nested {@code Document} are package-private. Registering them from outside would mean widening their
 * visibility to satisfy a build concern, which is the wrong trade.
 *
 * <p>All three failures were runtime 500s in native mode only, with JVM mode unaffected.
 * {@code WireContractReflectionTest} now asserts this list stays complete so the next addition to the
 * persistence model fails in {@code mvn verify} instead.
 */
@RegisterForReflection(targets = {
    BuildRecord.class,
    BuildRecord.CellRecord.class,
    BuildItems.Document.class,
})
public final class PersistenceReflectionConfig {

    private PersistenceReflectionConfig() {
    }
}
