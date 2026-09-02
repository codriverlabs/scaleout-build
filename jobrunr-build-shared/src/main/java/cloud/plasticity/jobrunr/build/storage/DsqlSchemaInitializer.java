/*
 * Copyright © 2026 Plasticity.Cloud and CoDriverLabs. All rights reserved.
 */
package cloud.plasticity.jobrunr.build.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates the JobRunr schema for one architecture directly against Aurora DSQL.
 *
 * <p>JobRunr's own {@code DatabaseCreator} cannot be used against DSQL. It sends each migration
 * file as a single JDBC {@code Statement.execute(fileContents)} call, and the shipped PostgreSQL
 * migration files mix multiple {@code CREATE INDEX} statements, and DDL with DML, in one file — all
 * of which DSQL rejects, since it allows at most one DDL statement per transaction and requires
 * {@code CREATE INDEX ASYNC} instead of synchronous index creation. There is also no {@code NCHAR}
 * type in DSQL's supported PostgreSQL subset, which JobRunr's schema uses for every primary key.
 *
 * <p>This class instead applies a hand-adapted target schema — the current shape JobRunr's
 * migrations converge to as of jobrunr 8.8.2, not a replay of its migration history — with each
 * statement in its own transaction, and DDL followed by any dependent DML in the correct order.
 * JobRunr must then be configured with {@code DatabaseOptions.SKIP_CREATE} so it never attempts its
 * own migrations against the tables this class creates.
 *
 * <p>Uses the {@code admin} database role ({@code dsql:DbConnectAdmin}), reserved for setup, never
 * for the runtime worker connections that {@link DsqlDataSourceFactory} creates.
 */
public final class DsqlSchemaInitializer {

    private static final Logger LOG = LoggerFactory.getLogger(DsqlSchemaInitializer.class);

    private DsqlSchemaInitializer() {
    }

    /**
     * Statements to create one architecture's JobRunr schema, in order, one per transaction.
     *
     * <p>{@code tablePrefix} must end with the separator, e.g. {@code jobrunr_x86_64.} — the same
     * value passed to JobRunr's {@code PostgresStorageProvider}.
     */
    public static List<String> createSchemaStatements(String tablePrefix) {
        requireTablePrefix(tablePrefix);
        String jobs = tablePrefix + "jobs";
        String recurringJobs = tablePrefix + "recurring_jobs";
        String backgroundJobServers = tablePrefix + "backgroundjobservers";
        String metadata = tablePrefix + "metadata";
        String migrations = tablePrefix + "migrations";
        String jobsStats = tablePrefix + "jobs_stats";

        List<String> statements = new ArrayList<>();

        // jobrunr_migrations: present so tooling that inspects it (including a human checking "did
        // setup run") finds the same table SKIP_CREATE would otherwise have left JobRunr to manage;
        // this class does not use it itself, since it is not a migration runner.
        statements.add("""
                CREATE TABLE IF NOT EXISTS %s (
                    id          CHAR(36) PRIMARY KEY,
                    script      VARCHAR(64) NOT NULL,
                    installedOn VARCHAR(29) NOT NULL
                )
                """.formatted(migrations));

        statements.add("""
                CREATE TABLE IF NOT EXISTS %s (
                    id           CHAR(36) PRIMARY KEY,
                    version      INTEGER      NOT NULL,
                    jobAsJson    TEXT         NOT NULL,
                    jobSignature VARCHAR(512) NOT NULL,
                    state        VARCHAR(36)  NOT NULL,
                    createdAt    TIMESTAMP    NOT NULL,
                    updatedAt    TIMESTAMP    NOT NULL,
                    scheduledAt  TIMESTAMP,
                    recurringJobId VARCHAR(128)
                )
                """.formatted(jobs));
        statements.add(
                "CREATE INDEX ASYNC IF NOT EXISTS jobrunr_state_idx ON %s (state)".formatted(jobs));
        statements.add(
                "CREATE INDEX ASYNC IF NOT EXISTS jobrunr_job_signature_idx ON %s (jobSignature)"
                        .formatted(jobs));
        statements.add(
                "CREATE INDEX ASYNC IF NOT EXISTS jobrunr_job_created_at_idx ON %s (createdAt)"
                        .formatted(jobs));
        statements.add(
                "CREATE INDEX ASYNC IF NOT EXISTS jobrunr_jobs_state_updated_idx ON %s "
                        .formatted(jobs)
                        + "(state ASC, updatedAt ASC)");
        statements.add(
                "CREATE INDEX ASYNC IF NOT EXISTS jobrunr_job_scheduled_at_idx ON %s (scheduledAt)"
                        .formatted(jobs));
        statements.add(
                "CREATE INDEX ASYNC IF NOT EXISTS jobrunr_job_rci_idx ON %s (recurringJobId)"
                        .formatted(jobs));

        statements.add("""
                CREATE TABLE IF NOT EXISTS %s (
                    id        VARCHAR(128) PRIMARY KEY,
                    version   INTEGER NOT NULL,
                    jobAsJson TEXT    NOT NULL,
                    createdAt BIGINT  NOT NULL DEFAULT 0
                )
                """.formatted(recurringJobs));
        statements.add(
                "CREATE INDEX ASYNC IF NOT EXISTS jobrunr_recurring_job_created_at_idx ON %s "
                        .formatted(recurringJobs)
                        + "(createdAt)");

        statements.add("""
                CREATE TABLE IF NOT EXISTS %s (
                    id                     CHAR(36)      PRIMARY KEY,
                    workerPoolSize         INTEGER       NOT NULL,
                    pollIntervalInSeconds  INTEGER       NOT NULL,
                    firstHeartbeat         TIMESTAMP     NOT NULL,
                    lastHeartbeat          TIMESTAMP     NOT NULL,
                    running                INTEGER       NOT NULL,
                    systemTotalMemory      BIGINT        NOT NULL,
                    systemFreeMemory       BIGINT        NOT NULL,
                    systemCpuLoad          NUMERIC(3, 2) NOT NULL,
                    processMaxMemory       BIGINT        NOT NULL,
                    processFreeMemory      BIGINT        NOT NULL,
                    processAllocatedMemory BIGINT        NOT NULL,
                    processCpuLoad         NUMERIC(3, 2) NOT NULL,
                    deleteSucceededJobsAfter   VARCHAR(32),
                    permanentlyDeleteJobsAfter VARCHAR(32),
                    name                       VARCHAR(128)
                )
                """.formatted(backgroundJobServers));
        statements.add(
                "CREATE INDEX ASYNC IF NOT EXISTS jobrunr_bgjobsrvrs_fsthb_idx ON %s (firstHeartbeat)"
                        .formatted(backgroundJobServers));
        statements.add(
                "CREATE INDEX ASYNC IF NOT EXISTS jobrunr_bgjobsrvrs_lsthb_idx ON %s (lastHeartbeat)"
                        .formatted(backgroundJobServers));

        statements.add("""
                CREATE TABLE IF NOT EXISTS %s (
                    id        VARCHAR(156) PRIMARY KEY,
                    name      VARCHAR(92)  NOT NULL,
                    owner     VARCHAR(64)  NOT NULL,
                    value     TEXT         NOT NULL,
                    createdAt TIMESTAMP    NOT NULL,
                    updatedAt TIMESTAMP    NOT NULL
                )
                """.formatted(metadata));

        // Final (v16) shape of the stats view: plain GROUP BY rather than GROUP BY ROLLUP, since
        // DSQL's supported GROUP BY clauses are ALL/DISTINCT only, not ROLLUP.
        statements.add("""
                CREATE OR REPLACE VIEW %s AS
                WITH job_stat_results AS (
                    SELECT state, count(*) AS count FROM %s GROUP BY state
                )
                SELECT coalesce((SELECT sum(count) FROM job_stat_results), 0) AS total,
                       coalesce((SELECT sum(count) FROM job_stat_results WHERE state = 'AWAITING'), 0)
                           AS awaiting,
                       coalesce((SELECT sum(count) FROM job_stat_results WHERE state = 'SCHEDULED'), 0)
                           AS scheduled,
                       coalesce((SELECT sum(count) FROM job_stat_results WHERE state = 'ENQUEUED'), 0)
                           AS enqueued,
                       coalesce((SELECT sum(count) FROM job_stat_results WHERE state = 'PROCESSING'), 0)
                           AS processing,
                       coalesce((SELECT sum(count) FROM job_stat_results WHERE state = 'PROCESSED'), 0)
                           AS processed,
                       coalesce((SELECT sum(count) FROM job_stat_results WHERE state = 'FAILED'), 0)
                           AS failed,
                       coalesce((SELECT sum(count) FROM job_stat_results WHERE state = 'SUCCEEDED'), 0)
                           AS succeeded,
                       0 AS allTimeSucceeded,
                       coalesce((SELECT sum(count) FROM job_stat_results WHERE state = 'DELETED'), 0)
                           AS deleted,
                       (SELECT count(*) FROM %s) AS nbrOfBackgroundJobServers,
                       (SELECT count(*) FROM %s) AS nbrOfRecurringJobs
                """.formatted(jobsStats, jobs, backgroundJobServers, recurringJobs));

        return List.copyOf(statements);
    }

    /**
     * Applies {@link #createSchemaStatements(String)} to {@code dataSource}, one statement per
     * transaction, in order. Statements use {@code IF NOT EXISTS}/{@code CREATE OR REPLACE}, so this
     * is safe to run more than once (idempotent setup), but is not a migration runner — it always
     * creates the current target shape, never an older one.
     *
     * @param dataSource  a connection to the DSQL cluster authenticated as the {@code admin} role
     * @param tablePrefix schema/table prefix for one architecture, e.g. {@code jobrunr_x86_64.}
     */
    public static void initialize(DataSource dataSource, String tablePrefix) throws SQLException {
        Objects.requireNonNull(dataSource, "dataSource");
        applyStatements(dataSource, createSchemaStatements(tablePrefix), tablePrefix);
    }

    /**
     * Applies an arbitrary statement list, one per transaction, in order.
     *
     * <p>Package-visible so tests can apply a Postgres-compatible variant of the generated
     * statements (e.g. with DSQL's {@code ASYNC} keyword stripped) through the same
     * one-statement-per-transaction mechanics {@link #initialize} uses, without duplicating it.
     */
    static void applyStatements(DataSource dataSource, List<String> statements, String label)
            throws SQLException {
        LOG.info("Applying {} schema statement(s) for '{}'", statements.size(), label);
        try (Connection connection = dataSource.getConnection()) {
            for (String statement : statements) {
                runInOwnTransaction(connection, statement);
            }
        }
        LOG.info("Schema ready for '{}'", label);
    }

    /**
     * Runs one statement in its own transaction — required because DSQL allows at most one DDL
     * statement per transaction, and mixing autocommit off with manual commit is what makes "one
     * statement, one transaction" explicit rather than accidental.
     */
    private static void runInOwnTransaction(Connection connection, String sql) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw new SQLException("Failed to apply statement: " + firstLine(sql), e);
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private static String firstLine(String sql) {
        int newline = sql.indexOf('\n');
        return (newline < 0 ? sql : sql.substring(0, newline)).trim();
    }

    private static void requireTablePrefix(String tablePrefix) {
        if (tablePrefix == null || tablePrefix.isBlank()) {
            throw new IllegalArgumentException("tablePrefix must not be blank");
        }
        if (!tablePrefix.endsWith(".") && !tablePrefix.endsWith("_")) {
            throw new IllegalArgumentException(
                    "tablePrefix must end with a separator ('.' or '_'), was: " + tablePrefix);
        }
    }
}
