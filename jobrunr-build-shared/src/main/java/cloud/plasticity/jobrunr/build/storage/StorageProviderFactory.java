/*
 * Copyright © 2026 Plasticity.Cloud and CoDriverLabs. All rights reserved.
 */
package cloud.plasticity.jobrunr.build.storage;

import cloud.plasticity.jobrunr.build.Architecture;
import java.util.Objects;
import javax.sql.DataSource;
import org.jobrunr.jobs.mappers.JobMapper;
import org.jobrunr.storage.InMemoryStorageProvider;
import org.jobrunr.storage.StorageProvider;
import org.jobrunr.storage.StorageProviderUtils;
import org.jobrunr.storage.sql.postgres.PostgresStorageProvider;
import org.jobrunr.utils.mapper.JsonMapper;
import org.jobrunr.utils.mapper.jackson.JacksonJsonMapper;

/**
 * Builds ready-to-use JobRunr storage providers.
 *
 * <p>Wires the {@code JobMapper} explicitly. JobRunr's framework integrations normally do this, and a
 * provider without one throws a {@link NullPointerException} the first time it serialises a job — so
 * doing it here is what keeps the plugin and agent free of JobRunr's static configuration.
 *
 * <p>For {@link org.jobrunr.storage.sql.SqlStorageProvider} backings ({@code DSQL}, {@code
 * POSTGRES}), the returned provider always uses {@link
 * StorageProviderUtils.DatabaseOptions#SKIP_CREATE}. JobRunr's automatic table creation is never
 * used against DSQL — see {@link DsqlSchemaInitializer} for why — and disabling it uniformly for
 * both backings keeps schema setup a single, explicit, out-of-band step regardless of which one is
 * configured.
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
     * Creates a provider for {@link StorageType#IN_MEMORY} settings, which need no architecture.
     *
     * @throws IllegalArgumentException if {@code settings} is not {@code IN_MEMORY} — use
     *         {@link #create(StorageSettings, Architecture, JsonMapper)} for backings that need a
     *         per-architecture table prefix
     */
    public static StorageProvider create(StorageSettings settings, JsonMapper jsonMapper) {
        Objects.requireNonNull(settings, "settings");
        if (settings.type() != StorageType.IN_MEMORY) {
            throw new IllegalArgumentException(
                    settings.type() + " storage needs an architecture; use "
                            + "create(StorageSettings, Architecture, JsonMapper) instead");
        }
        return create(settings, null, jsonMapper);
    }

    /**
     * Creates a provider for the given settings and one architecture's schema.
     *
     * <p>For {@link StorageType#IN_MEMORY} the returned instance <em>is</em> the store, so the
     * enqueuing and processing sides must share it. That is only viable in one JVM, which is exactly
     * the local mode and test scenario it exists for.
     *
     * @param architecture required for {@code DSQL}/{@code POSTGRES}, since those backings need a
     *                     per-architecture table prefix; ignored for {@code IN_MEMORY}
     */
    public static StorageProvider create(StorageSettings settings, Architecture architecture) {
        return create(settings, architecture, jsonMapper());
    }

    public static StorageProvider create(StorageSettings settings, Architecture architecture,
                                         JsonMapper jsonMapper) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(jsonMapper, "jsonMapper");

        StorageProvider provider = switch (settings.type()) {
            case IN_MEMORY -> new InMemoryStorageProvider();
            case DSQL -> {
                Objects.requireNonNull(architecture, "architecture is required for DSQL storage");
                DataSource dataSource = DsqlDataSourceFactory.create(settings.dsqlConnectionSettings());
                yield new PostgresStorageProvider(dataSource, settings.tablePrefix(architecture),
                        StorageProviderUtils.DatabaseOptions.SKIP_CREATE);
            }
            case POSTGRES -> throw new UnsupportedOperationException(
                    "POSTGRES storage is documented as the DSQL fallback but its DataSource wiring "
                            + "is not yet implemented; construct a DataSource and a "
                            + "PostgresStorageProvider directly until it is.");
        };
        provider.setJobMapper(new JobMapper(jsonMapper));
        return provider;
    }
}

