/*
 * Copyright © 2026 Plasticity.Cloud and CoDriverLabs. All rights reserved.
 */
package cloud.plasticity.jobrunr.build.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Validates the hand-adapted schema against a real PostgreSQL server.
 *
 * <p>This is not a substitute for testing against Aurora DSQL itself — plain PostgreSQL accepts
 * {@code GROUP BY ROLLUP}, multi-statement transactions and {@code NCHAR}, none of which DSQL does,
 * so a green run here does not prove DSQL compatibility. Conversely, plain PostgreSQL does not
 * understand DSQL's {@code CREATE INDEX ASYNC} syntax, so the two integration tests below run a
 * version of the statements with {@code ASYNC} stripped — that is a test-only accommodation for
 * Postgres, not a production code path; {@link #statementsCoverTheTablesJobRunrsPostgresStorageProviderExpects()}
 * asserts that the real, unmodified statements always include {@code ASYNC}.
 *
 * <p>What the integration tests do prove: every table/view statement this class generates is valid,
 * executable SQL against a real Postgres-wire-protocol server, each one commits independently, and
 * the resulting {@code jobrunr_jobs_stats} view matches the shape JobRunr's
 * {@code PostgresStorageProvider} queries expect. Combined with the DSQL documentation checks
 * already encoded in the adapted statements themselves (async indexes, no {@code NCHAR}, one
 * statement per transaction), this is the practical ceiling of what can be verified without a live
 * DSQL cluster.
 */
@Testcontainers
class DsqlSchemaInitializerTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    @Timeout(60)
    void createsAllExpectedTablesAndTheStatsView() throws SQLException {
        String schema = "jobrunr_x86_64";
        String tablePrefix = schema + ".";
        createSchemaNamespace(schema);

        var dataSource = dataSourceForSchema(schema);
        initializeForPlainPostgres(dataSource, tablePrefix);

        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            var tables = statement.executeQuery("""
                    SELECT table_name FROM information_schema.tables
                    WHERE table_schema = '%s'
                    """.formatted(schema));
            java.util.List<String> tableNames = new java.util.ArrayList<>();
            while (tables.next()) {
                tableNames.add(tables.getString(1));
            }
            assertThat(tableNames).containsExactlyInAnyOrder(
                    "jobs", "recurring_jobs", "backgroundjobservers", "metadata", "migrations",
                    "jobs_stats");

            var statsRow = statement.executeQuery("SELECT * FROM " + tablePrefix + "jobs_stats");
            assertThat(statsRow.next()).isTrue();
            assertThat(statsRow.getLong("total")).isZero();
            assertThat(statsRow.getLong("nbrOfBackgroundJobServers")).isZero();
            assertThat(statsRow.getLong("nbrOfRecurringJobs")).isZero();
        }
    }

    @Test
    @Timeout(60)
    void isIdempotent() throws SQLException {
        String schema = "jobrunr_arm64";
        String tablePrefix = schema + ".";
        createSchemaNamespace(schema);

        var dataSource = dataSourceForSchema(schema);
        initializeForPlainPostgres(dataSource, tablePrefix);
        // Running it again must not fail: every CREATE TABLE/INDEX uses IF NOT EXISTS and the view
        // uses CREATE OR REPLACE.
        initializeForPlainPostgres(dataSource, tablePrefix);
    }

    @Test
    void rejectsATablePrefixWithoutASeparator() {
        assertThatThrownBy(() -> DsqlSchemaInitializer.createSchemaStatements("jobrunr"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("separator");
    }

    @Test
    void statementsCoverTheTablesJobRunrsPostgresStorageProviderExpects() {
        var statements = DsqlSchemaInitializer.createSchemaStatements("jobrunr_x86_64.");

        assertThat(statements).anySatisfy(s -> assertThat(s).contains("jobrunr_x86_64.jobs"));
        assertThat(statements)
                .anySatisfy(s -> assertThat(s).contains("jobrunr_x86_64.recurring_jobs"));
        assertThat(statements)
                .anySatisfy(s -> assertThat(s).contains("jobrunr_x86_64.backgroundjobservers"));
        assertThat(statements).anySatisfy(s -> assertThat(s).contains("jobrunr_x86_64.metadata"));
        assertThat(statements).anySatisfy(s -> assertThat(s).contains("jobrunr_x86_64.jobs_stats"));
        // Every index must use the ASYNC keyword DSQL requires.
        assertThat(statements.stream().filter(s -> s.contains("CREATE INDEX")))
                .allSatisfy(s -> assertThat(s).contains("CREATE INDEX ASYNC"));
        // No statement may use NCHAR, which is not in DSQL's supported type list.
        assertThat(statements).noneSatisfy(s -> assertThat(s.toUpperCase(java.util.Locale.ROOT))
                .contains("NCHAR"));
        // Every %s placeholder must have been substituted; an unresolved one is a formatting bug.
        assertThat(statements).noneSatisfy(s -> assertThat(s).contains("%s"));
    }

    private static void initializeForPlainPostgres(javax.sql.DataSource dataSource,
                                                    String tablePrefix) throws SQLException {
        var statements = DsqlSchemaInitializer.createSchemaStatements(tablePrefix).stream()
                .map(s -> s.replace("CREATE INDEX ASYNC", "CREATE INDEX"))
                .toList();
        DsqlSchemaInitializer.applyStatements(dataSource, statements, tablePrefix);
    }

    private static void createSchemaNamespace(String schema) throws SQLException {
        try (var connection = POSTGRES.createConnection("");
                var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
        }
    }

    /** A {@link javax.sql.DataSource} whose connections default to the given schema. */
    private static org.postgresql.ds.PGSimpleDataSource dataSourceForSchema(String schema) {
        var dataSource = new org.postgresql.ds.PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        dataSource.setCurrentSchema(schema);
        return dataSource;
    }
}
