/*
 * Copyright © 2026 Plasticity.Cloud and CoDriverLabs. All rights reserved.
 */
package cloud.plasticity.jobrunr.build.storage;

import java.util.Objects;
import org.jobrunr.jobs.mappers.JobMapper;
import org.jobrunr.storage.InMemoryStorageProvider;
import org.jobrunr.storage.StorageProvider;
import org.jobrunr.utils.mapper.JsonMapper;
import org.jobrunr.utils.mapper.jackson.JacksonJsonMapper;

/**
 * Builds ready-to-use JobRunr storage providers.
 *
 * <p>Wires the {@code JobMapper} explicitly. JobRunr's framework integrations normally do this, and a
 * provider without one throws a {@link NullPointerException} the first time it serialises a job — so
 * doing it here is what keeps the plugin and agent free of JobRunr's static configuration.
 */
public final class StorageProviderFactory {

    private StorageProviderFactory() {
    }

    /**
     * The JSON mapper used for job payloads. Jackson 2 is chosen over Gson because the plugin already
     * pulls Jackson in through the AWS SDK, keeping the shaded agent jar smaller.
     */
    public static JsonMapper jsonMapper() {
        return new JacksonJsonMapper();
    }

    /**
     * Creates a provider for the given settings.
     *
     * <p>For {@link StorageType#IN_MEMORY} the returned instance <em>is</em> the store, so the
     * enqueuing and processing sides must share it. That is only viable in one JVM, which is exactly
     * the local mode and test scenario it exists for.
     */
    public static StorageProvider create(StorageSettings settings) {
        return create(settings, jsonMapper());
    }

    public static StorageProvider create(StorageSettings settings, JsonMapper jsonMapper) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(jsonMapper, "jsonMapper");

        StorageProvider provider = switch (settings.type()) {
            case IN_MEMORY -> new InMemoryStorageProvider();
        };
        provider.setJobMapper(new JobMapper(jsonMapper));
        return provider;
    }
}
