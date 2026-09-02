/*
 * Copyright © 2026 Plasticity.Cloud and CoDriverLabs. All rights reserved.
 */
package cloud.plasticity.jobrunr.build.storage;

import java.time.Duration;
import java.util.Objects;

/**
 * How to reach one Aurora DSQL cluster.
 *
 * <p>Deliberately carries no password or static credential: authentication is always an IAM auth
 * token generated per connection by the DSQL JDBC Connector, using whatever AWS credentials are
 * active in the process (default credential chain). See {@code DsqlDataSourceFactory}.
 *
 * @param clusterEndpoint DSQL cluster endpoint host, e.g. {@code abcd1234.dsql.us-east-1.on.aws} —
 *                        no scheme, port or path
 * @param region          AWS region the cluster lives in, e.g. {@code us-east-1}
 * @param databaseUser    database role to connect as. Use a scoped role with {@code dsql:DbConnect}
 *                        for normal runtime use; the {@code admin} role (requiring
 *                        {@code dsql:DbConnectAdmin}) is reserved for {@link DsqlSchemaInitializer}
 * @param maxPoolSize     maximum HikariCP pool size
 * @param maxLifetime     maximum physical connection lifetime, forced under DSQL's 60-minute
 *                        connection cap by {@code DsqlDataSourceFactory}
 */
public record DsqlConnectionSettings(
        String clusterEndpoint,
        String region,
        String databaseUser,
        int maxPoolSize,
        Duration maxLifetime) {

    /** DSQL terminates connections after this long; a pool's maxLifetime must stay under it. */
    public static final Duration DSQL_MAX_CONNECTION_DURATION = Duration.ofMinutes(60);

    /** Safety margin under {@link #DSQL_MAX_CONNECTION_DURATION} used when none is specified. */
    public static final Duration DEFAULT_MAX_LIFETIME = Duration.ofMinutes(50);

    public DsqlConnectionSettings(String clusterEndpoint, String region, String databaseUser,
                                  int maxPoolSize, Duration maxLifetime) {
        if (clusterEndpoint == null || clusterEndpoint.isBlank()) {
            throw new IllegalArgumentException("clusterEndpoint must not be blank");
        }
        if (clusterEndpoint.contains("://") || clusterEndpoint.contains("/")) {
            throw new IllegalArgumentException(
                    "clusterEndpoint must be a bare host, not a URL: " + clusterEndpoint);
        }
        if (region == null || region.isBlank()) {
            throw new IllegalArgumentException("region must not be blank");
        }
        if (databaseUser == null || databaseUser.isBlank()) {
            throw new IllegalArgumentException("databaseUser must not be blank");
        }
        if (maxPoolSize < 1) {
            throw new IllegalArgumentException("maxPoolSize must be at least 1");
        }
        Duration effectiveMaxLifetime = maxLifetime == null ? DEFAULT_MAX_LIFETIME : maxLifetime;
        if (effectiveMaxLifetime.compareTo(DSQL_MAX_CONNECTION_DURATION) >= 0) {
            throw new IllegalArgumentException(
                    "maxLifetime (" + effectiveMaxLifetime + ") must be strictly less than DSQL's "
                            + DSQL_MAX_CONNECTION_DURATION + " connection cap, or every pooled "
                            + "connection will be force-closed by the server mid-use");
        }
        this.clusterEndpoint = clusterEndpoint.trim();
        this.region = region.trim();
        this.databaseUser = databaseUser.trim();
        this.maxPoolSize = maxPoolSize;
        this.maxLifetime = effectiveMaxLifetime;
    }

    /** Convenience constructor using the default pool size and max lifetime. */
    public static DsqlConnectionSettings of(String clusterEndpoint, String region,
                                            String databaseUser) {
        return new DsqlConnectionSettings(clusterEndpoint, region, databaseUser, 5, null);
    }

    /** The single-database JDBC URL DSQL exposes, via the DSQL JDBC Connector's URL scheme. */
    public String jdbcUrl() {
        return "jdbc:aws-dsql:postgresql://" + clusterEndpoint + "/postgres";
    }

    @Override
    public String toString() {
        // Deliberately omits databaseUser from nothing sensitive here, but keeps the format
        // predictable for logs; there is no password or token to redact since none is ever stored.
        return "DsqlConnectionSettings{clusterEndpoint=" + clusterEndpoint + ", region=" + region
                + ", databaseUser=" + databaseUser + ", maxPoolSize=" + maxPoolSize
                + ", maxLifetime=" + maxLifetime + '}';
    }
}
