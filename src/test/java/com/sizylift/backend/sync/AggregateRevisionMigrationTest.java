package com.sizylift.backend.sync;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import com.sizylift.backend.PostgresIntegrationTest;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Заполнение ревизий для данных, опубликованных до sync v2 (ADR-0007, раздел 9). */
class AggregateRevisionMigrationTest extends PostgresIntegrationTest {

    static final String CHANGELOG = "db/changelog/db.changelog-master.yaml";
    static final String FIRST_REVISION_CHANGESET_FILE = "008-add-aggregate-revisions";
    static final Instant UPDATED = Instant.parse("2026-10-01T10:00:00Z");
    static final Instant RECEIVED = Instant.parse("2026-10-02T11:00:00Z");

    @Test
    void publishedAggregatesGetRevisionsAndChangedAtFromTheirRoots() throws Exception {
        var user = UUID.randomUUID();
        var injury = UUID.randomUUID();
        var oneRepMax = UUID.randomUUID();
        var tombstone = UUID.randomUUID();
        try (var connection = emptyDatabase("revision_backfill")) {
            var liquibase = liquibase(connection);
            liquibase.update(changesetsBeforeRevisions(liquibase), "");

            insertLegacyData(connection, user, injury, oneRepMax, tombstone);
            liquibase.update(new Contexts(), new LabelExpression());

            var rows = connection.createStatement().executeQuery("""
                    SELECT aggregate_id, revision, changed_at, author_user_id, source_client_id
                    FROM sizylift.published_aggregate ORDER BY aggregate_id
                    """);
            var changedAt = new java.util.HashMap<UUID, Instant>();
            var revisions = new java.util.HashSet<Long>();
            while (rows.next()) {
                changedAt.put(rows.getObject("aggregate_id", UUID.class), rows.getTimestamp("changed_at").toInstant());
                revisions.add(rows.getLong("revision"));
                assertThat(rows.getObject("author_user_id")).isNull();
                assertThat(rows.getString("source_client_id")).isNull();
            }
            assertThat(changedAt).containsEntry(injury, UPDATED).containsEntry(oneRepMax, UPDATED)
                    .containsEntry(tombstone, RECEIVED);
            assertThat(revisions).hasSize(3).doesNotContain(0L);

            var next = connection.createStatement()
                    .executeQuery("SELECT nextval('sizylift.aggregate_revision_seq')");
            next.next();
            assertThat(next.getLong(1)).isGreaterThan(revisions.stream().mapToLong(Long::longValue).max().orElseThrow());
        }
    }

    private static int changesetsBeforeRevisions(Liquibase liquibase) throws Exception {
        var unrun = liquibase.listUnrunChangeSets(new Contexts(), new LabelExpression());
        for (int i = 0; i < unrun.size(); i++) {
            if (unrun.get(i).getFilePath().contains(FIRST_REVISION_CHANGESET_FILE)) {
                return i;
            }
        }
        throw new IllegalStateException("Changeset " + FIRST_REVISION_CHANGESET_FILE + " not found");
    }

    private static void insertLegacyData(Connection connection, UUID user, UUID injury, UUID oneRepMax,
                                         UUID tombstone) throws Exception {
        try (var statement = connection.prepareStatement(
                "INSERT INTO sizylift.user_account (id, issuer, subject) VALUES (?, 'issuer', ?)")) {
            statement.setObject(1, user);
            statement.setString(2, user.toString());
            statement.executeUpdate();
        }
        try (var statement = connection.prepareStatement("""
                INSERT INTO sizylift.injury (user_id, id, name, status, updated_at) VALUES (?, ?, 'Колено', 'active', ?)
                """)) {
            statement.setObject(1, user);
            statement.setObject(2, injury);
            statement.setTimestamp(3, Timestamp.from(UPDATED));
            statement.executeUpdate();
        }
        try (var statement = connection.prepareStatement("""
                INSERT INTO sizylift.one_rep_max (user_id, exercise_id, weight_grams, achieved_on, updated_at)
                VALUES (?, ?, 100000, '2026-09-20', ?)
                """)) {
            statement.setObject(1, user);
            statement.setObject(2, oneRepMax);
            statement.setTimestamp(3, Timestamp.from(UPDATED));
            statement.executeUpdate();
        }
        publish(connection, user, "injury", injury, false);
        publish(connection, user, "one_rep_max", oneRepMax, false);
        publish(connection, user, "workout", tombstone, true);
    }

    private static void publish(Connection connection, UUID user, String type, UUID id, boolean deleted)
            throws Exception {
        try (var statement = connection.prepareStatement("""
                INSERT INTO sizylift.published_aggregate (user_id, type, aggregate_id, version, deleted, received_at)
                VALUES (?, ?, ?, 3, ?, ?)
                """)) {
            statement.setObject(1, user);
            statement.setString(2, type);
            statement.setObject(3, id);
            statement.setBoolean(4, deleted);
            statement.setTimestamp(5, Timestamp.from(RECEIVED));
            statement.executeUpdate();
        }
    }

    private static Liquibase liquibase(Connection connection) throws Exception {
        var database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
        database.setDefaultSchemaName("public");
        database.setLiquibaseSchemaName("public");
        return new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database);
    }

    private static Connection emptyDatabase(String name) throws Exception {
        try (var admin = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword())) {
            admin.createStatement().execute("CREATE DATABASE " + name);
        }
        var url = POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + name);
        return DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
