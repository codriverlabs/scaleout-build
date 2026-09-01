/*
 * Copyright © 2026 Plasticity.Cloud and CoDriverLabs. All rights reserved.
 */
package cloud.plasticity.jobrunr.build.storage;

import java.util.Locale;

/**
 * Supported JobRunr storage backings.
 *
 * <p>{@code DSQL} (Aurora DSQL) and {@code POSTGRES} (Aurora Serverless v2) arrive with the storage
 * task; only in-memory exists today, and it is the one used by tests and by local mode.
 */
public enum StorageType {

    /** Non-persistent, single-JVM. Enqueue and processing must share one provider instance. */
    IN_MEMORY;

    /** Parses a configuration value such as {@code inmemory}, {@code in-memory} or {@code mem}. */
    public static StorageType parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Storage type must not be blank; expected: inmemory");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        return switch (normalized) {
            case "inmemory", "mem", "memory" -> IN_MEMORY;
            default -> throw new IllegalArgumentException(
                    "Unsupported storage type '" + value + "'; expected: inmemory");
        };
    }
}
