package com.sizylift.backend.publication;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.sizylift.backend.PostgresIntegrationTest;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Перенос {@code is_sbd} в {@code lift_class} и разметка системного каталога (docs/coach-workspace.md, раздел 1). */
class LiftClassMigrationTest extends PostgresIntegrationTest {

    static final String CHANGELOG = "db/changelog/db.changelog-master.yaml";
    static final String LIFT_CLASS_CHANGESET_FILE = "019-exercise-lift-class";
    static final String BEFORE_LIFT_CLASS_TAG = "before-019";

    static final Set<String> SBD = Set.of(
            "14000000-0000-4000-8000-000000000001", "14000000-0000-4000-8000-000000000002",
            "14000000-0000-4000-8000-000000000003", "14000000-0000-4000-8000-000000000039");
    static final Set<String> BASIC = Set.of(
            "14000000-0000-4000-8000-000000000040", "14000000-0000-4000-8000-000000000043",
            "14000000-0000-4000-8000-000000000011", "14000000-0000-4000-8000-000000000044",
            "14000000-0000-4000-8000-000000000063", "14000000-0000-4000-8000-000000000022",
            "14000000-0000-4000-8000-000000000021", "14000000-0000-4000-8000-000000000023",
            "14000000-0000-4000-8000-000000000051", "14000000-0000-4000-8000-000000000052",
            "14000000-0000-4000-8000-000000000008", "14000000-0000-4000-8000-000000000012",
            "14000000-0000-4000-8000-000000000015", "14000000-0000-4000-8000-000000000016",
            "14000000-0000-4000-8000-000000000031", "14000000-0000-4000-8000-000000000047");

    @Test
    void mapsIsSbdAndMarksTheSystemCatalogThenRollsBack() throws Exception {
        var user = UUID.randomUUID();
        var ownSbd = UUID.randomUUID();
        var ownOther = UUID.randomUUID();
        var ownSbdDuration = UUID.randomUUID();
        try (var connection = emptyDatabase("lift_class_migration")) {
            var liquibase = liquibase(connection);
            liquibase.update(changesetsBeforeLiftClass(liquibase), "");
            liquibase.tag(BEFORE_LIFT_CLASS_TAG);
            insertUser(connection, user);
            insertExercise(connection, user, ownSbd, "weight_reps", true);
            insertExercise(connection, user, ownOther, "weight_reps", false);
            insertExercise(connection, user, ownSbdDuration, "duration", true);

            liquibase.update(new Contexts(), new LabelExpression());

            var classes = liftClasses(connection);
            assertThat(classes).containsEntry(ownSbd.toString(), "sbd").containsEntry(ownOther.toString(), "other")
                    .containsEntry(ownSbdDuration.toString(), "other");
            var system = systemClasses(connection);
            assertThat(keys(system, "sbd")).isEqualTo(SBD);
            assertThat(keys(system, "basic")).isEqualTo(BASIC);
            assertThat(system).hasSizeGreaterThan(SBD.size() + BASIC.size());
            assertThat(hasColumn(connection, "exercise", "is_sbd")).isFalse();
            assertThat(hasColumn(connection, "planned_set", "target_percent_one_rm_tenths")).isTrue();

            liquibase.rollback(BEFORE_LIFT_CLASS_TAG, "");

            assertThat(hasColumn(connection, "exercise", "lift_class")).isFalse();
            assertThat(hasColumn(connection, "planned_set", "target_percent_one_rm_tenths")).isFalse();
            var sbd = connection.createStatement().executeQuery(
                    "SELECT id::text FROM sizylift.exercise WHERE is_sbd ORDER BY id");
            var restored = new HashSet<String>();
            while (sbd.next()) {
                restored.add(sbd.getString(1));
            }
            assertThat(restored).containsAll(SBD).contains(ownSbd.toString()).doesNotContainAnyElementsOf(BASIC);
        }
    }

    private static Map<String, String> liftClasses(Connection connection) throws Exception {
        var rows = connection.createStatement().executeQuery("SELECT id::text, lift_class FROM sizylift.exercise");
        var classes = new HashMap<String, String>();
        while (rows.next()) {
            classes.put(rows.getString(1), rows.getString(2));
        }
        return classes;
    }

    private static Map<String, String> systemClasses(Connection connection) throws Exception {
        var rows = connection.createStatement().executeQuery(
                "SELECT id::text, lift_class FROM sizylift.exercise WHERE user_id IS NULL");
        var classes = new HashMap<String, String>();
        while (rows.next()) {
            classes.put(rows.getString(1), rows.getString(2));
        }
        return classes;
    }

    private static Set<String> keys(Map<String, String> classes, String liftClass) {
        return classes.entrySet().stream().filter(entry -> entry.getValue().equals(liftClass))
                .map(Map.Entry::getKey).collect(Collectors.toSet());
    }

    private static boolean hasColumn(Connection connection, String table, String column) throws Exception {
        try (var statement = connection.prepareStatement("""
                SELECT EXISTS (SELECT 1 FROM information_schema.columns
                               WHERE table_schema = 'sizylift' AND table_name = ? AND column_name = ?)
                """)) {
            statement.setString(1, table);
            statement.setString(2, column);
            var result = statement.executeQuery();
            result.next();
            return result.getBoolean(1);
        }
    }

    private static int changesetsBeforeLiftClass(Liquibase liquibase) throws Exception {
        var unrun = liquibase.listUnrunChangeSets(new Contexts(), new LabelExpression());
        for (int i = 0; i < unrun.size(); i++) {
            if (unrun.get(i).getFilePath().contains(LIFT_CLASS_CHANGESET_FILE)) {
                return i;
            }
        }
        throw new IllegalStateException("Changeset " + LIFT_CLASS_CHANGESET_FILE + " not found");
    }

    private static void insertUser(Connection connection, UUID user) throws Exception {
        try (var statement = connection.prepareStatement(
                "INSERT INTO sizylift.user_account (id, issuer, subject) VALUES (?, 'issuer', ?)")) {
            statement.setObject(1, user);
            statement.setString(2, user.toString());
            statement.executeUpdate();
        }
    }

    private static void insertExercise(Connection connection, UUID user, UUID id, String resultType,
                                       boolean isSbd) throws Exception {
        try (var statement = connection.prepareStatement("""
                INSERT INTO sizylift.exercise (id, user_id, name, result_type, is_sbd) VALUES (?, ?, 'Своё', ?, ?)
                """)) {
            statement.setObject(1, id);
            statement.setObject(2, user);
            statement.setString(3, resultType);
            statement.setBoolean(4, isSbd);
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
