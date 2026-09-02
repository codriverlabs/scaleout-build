/*
 * Copyright © 2026 Plasticity.Cloud and CoDriverLabs. All rights reserved.
 */
package cloud.plasticity.jobrunr.build.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;
import software.amazon.dsql.jdbc.DSQLConnector;

/**
 * Builds a pooled {@link DataSource} for Aurora DSQL.
 *
 * <p>Uses the DSQL JDBC Connector ({@code jdbc:aws-dsql:postgresql://...}) rather than the bare
 * PostgreSQL driver. The Connector is what generates and refreshes IAM auth tokens per connection;
 * a bare {@code org.postgresql.Driver} URL would authenticate once and then fail every new
 * connection after the first 15-minute token expiry. See the DSQL JDBC Connector documentation:
 * https://docs.aws.amazon.com/aurora-dsql/latest/userguide/SECTION_program-with-jdbc-connector.html
 *
 * <p>Pool lifetime is capped strictly under DSQL's 60-minute connection limit (enforced by
 * {@link DsqlConnectionSettings}), so HikariCP recycles a connection — and the Connector mints a
 * fresh token for its replacement — before the server forcibly drops it.
 */
public final class DsqlDataSourceFactory {

    private DsqlDataSourceFactory() {
    }

    /** Creates and starts a connection pool for the given DSQL cluster. */
    public static HikariDataSource create(DsqlConnectionSettings settings) {
        Objects.requireNonNull(settings, "settings");
        ensureDriverRegistered();

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(settings.jdbcUrl());
        config.setUsername(settings.databaseUser());
        // No password: the Connector authenticates with a per-connection IAM auth token instead.
        config.setMaximumPoolSize(settings.maxPoolSize());
        config.setMinimumIdle(0);
        config.setMaxLifetime(settings.maxLifetime().toMillis());
        config.setPoolName("jobrunr-dsql-" + settings.region());

        // Required DSQL Connector / TLS properties. wrapperPlugins=iam is what activates the
        // Connector's IAM-token-per-connection behaviour on top of the pgJDBC driver it wraps.
        config.addDataSourceProperty("wrapperPlugins", "iam");
        config.addDataSourceProperty("ssl", "true");
        config.addDataSourceProperty("sslmode", "verify-full");

        return new HikariDataSource(config);
    }

    /**
     * Registers the DSQL Connector's JDBC driver if it is not already registered.
     *
     * <p>Normally unnecessary: the driver ships a {@code META-INF/services/java.sql.Driver} entry
     * that {@link java.sql.DriverManager} loads automatically. The explicit check exists because the
     * container agent runs from a shaded fat jar (see {@code jobrunr-build-agent}'s shade
     * configuration), where {@code ServiceLoader} metadata occasionally needs the merge transformer
     * to have run correctly; calling this defensively costs nothing and fails loudly if it didn't.
     */
    private static void ensureDriverRegistered() {
        if (DSQLConnector.isRegistered()) {
            return;
        }
        try {
            DSQLConnector.register();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to register the Aurora DSQL JDBC driver", e);
        }
    }
}
