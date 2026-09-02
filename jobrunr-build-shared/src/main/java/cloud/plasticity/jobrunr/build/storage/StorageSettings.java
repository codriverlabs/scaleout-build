/*
 * Copyright © 2026 Plasticity.Cloud and CoDriverLabs. All rights reserved.
 */
package cloud.plasticity.jobrunr.build.storage;

import cloud.plasticity.jobrunr.build.Architecture;
import java.util.Objects;

/**
 * How to reach the JobRunr job store.
 *
 * <p>The table prefix is what keeps architectures apart. JobRunr's Server Tags feature is Pro-only,
 * so instead of tagging workers we give each architecture its own schema and point each worker at
 * exactly one of them; a worker then physically cannot see another architecture's jobs.
 */
public final class StorageSettings {

    private static final String DEFAULT_SCHEMA_PREFIX = "jobrunr_";

    private final StorageType type;
    private final String schemaPrefix;
    private final DsqlConnectionSettings dsqlConnectionSettings;

    private StorageSettings(StorageType type, String schemaPrefix,
                            DsqlConnectionSettings dsqlConnectionSettings) {
        this.type = Objects.requireNonNull(type, "type");
        this.schemaPrefix = Objects.requireNonNull(schemaPrefix, "schemaPrefix");
        this.dsqlConnectionSettings = dsqlConnectionSettings;
        if (type == StorageType.DSQL && dsqlConnectionSettings == null) {
            throw new IllegalArgumentException("DSQL storage requires DsqlConnectionSettings");
        }
    }

    public static StorageSettings inMemory() {
        return new StorageSettings(StorageType.IN_MEMORY, DEFAULT_SCHEMA_PREFIX, null);
    }

    /** Settings for Aurora DSQL storage, schema-isolated per architecture. */
    public static StorageSettings dsql(DsqlConnectionSettings connectionSettings) {
        return dsql(connectionSettings, DEFAULT_SCHEMA_PREFIX);
    }

    public static StorageSettings dsql(DsqlConnectionSettings connectionSettings,
                                       String schemaPrefix) {
        Objects.requireNonNull(connectionSettings, "connectionSettings");
        return new StorageSettings(StorageType.DSQL,
                schemaPrefix == null || schemaPrefix.isBlank() ? DEFAULT_SCHEMA_PREFIX : schemaPrefix,
                connectionSettings);
    }

    public static StorageSettings of(StorageType type, String schemaPrefix) {
        if (type == StorageType.DSQL) {
            throw new IllegalArgumentException(
                    "Use StorageSettings.dsql(DsqlConnectionSettings) for DSQL storage");
        }
        return new StorageSettings(type,
                schemaPrefix == null || schemaPrefix.isBlank() ? DEFAULT_SCHEMA_PREFIX : schemaPrefix,
                null);
    }

    public StorageType type() {
        return type;
    }

    /** Base prefix for schema names, {@code jobrunr_} by default. */
    public String schemaPrefix() {
        return schemaPrefix;
    }

    /** Present only for {@link StorageType#DSQL}. */
    public DsqlConnectionSettings dsqlConnectionSettings() {
        return dsqlConnectionSettings;
    }

    /** Schema for one architecture, for example {@code jobrunr_x86_64}. */
    public String schemaName(Architecture architecture) {
        Objects.requireNonNull(architecture, "architecture");
        return schemaPrefix + architecture.schemaSuffix();
    }

    /**
     * JobRunr table prefix for one architecture, for example {@code jobrunr_x86_64.} — the trailing
     * separator is required, since JobRunr concatenates the prefix onto table names verbatim.
     */
    public String tablePrefix(Architecture architecture) {
        return schemaName(architecture) + ".";
    }
}
