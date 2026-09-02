/*
 * Copyright © 2026 Plasticity.Cloud and CoDriverLabs. All rights reserved.
 */
package cloud.plasticity.jobrunr.build.storage;

import java.util.Locale;

/**
 * Supported JobRunr storage backings.
 *
 * <p>{@link #DSQL} (Aurora DSQL) is the primary target: it exposes a public IAM-authenticated
 * endpoint, so a plugin invocation from a laptop can reach it without a VPN, bastion, or being
 * inside a VPC. {@link #POSTGRES} (plain PostgreSQL, e.g. Aurora Serverless v2) is the documented
 * fallback if DSQL's constraints turn out to be blocking; it is VPC-only, so it only becomes usable
 * once the plugin or its invoker has network access into that VPC.
 */
public enum StorageType {

    /** Non-persistent, single-JVM. Enqueue and processing must share one provider instance. */
    IN_MEMORY,

    /**
     * Aurora DSQL over its PostgreSQL wire protocol, reached through the DSQL JDBC Connector for
     * automatic IAM auth token generation and rotation. Schema is never created by JobRunr itself —
     * see {@link DsqlSchemaInitializer} — because DSQL rejects the multi-statement, mixed DDL/DML
     * migration files JobRunr's own {@code DatabaseCreator} sends as a single call.
     */
    DSQL,

    /** Plain PostgreSQL (or a wire-compatible service such as Aurora Serverless v2). */
    POSTGRES;

    /** Parses a configuration value such as {@code inmemory}, {@code dsql} or {@code postgres}. */
    public static StorageType parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Storage type must not be blank; expected one of: inmemory, dsql, postgres");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        return switch (normalized) {
            case "inmemory", "mem", "memory" -> IN_MEMORY;
            case "dsql", "auroradsql" -> DSQL;
            case "postgres", "postgresql", "pg" -> POSTGRES;
            default -> throw new IllegalArgumentException(
                    "Unsupported storage type '" + value + "'; expected one of: inmemory, dsql, "
                            + "postgres");
        };
    }
}

