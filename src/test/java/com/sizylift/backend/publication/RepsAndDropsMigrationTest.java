package com.sizylift.backend.publication;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
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

/** Changeset 038: «только повторы» и перенос прежних дроп-подходов в ступени подхода (docs/publication.md). */
class RepsAndDropsMigrationTest extends PostgresIntegrationTest {

    static final String CHANGELOG = "db/changelog/db.changelog-master.yaml";
    static final String CHANGESET_FILE = "038-reps-and-drops";
    static final String LEG_RAISES = "14000000-0000-4000-8000-000000000066";
    static final String PUSH_UPS = "14000000-0000-4000-8000-000000000030";
    static final String BENCH = "14000000-0000-4000-8000-000000000002";

    private final UUID user = UUID.randomUUID();
    private final UUID workout = UUID.randomUUID();
    private final UUID block = UUID.randomUUID();

    @Test
    void convertsWeightlessHistoryAndLegacyDropSets() throws Exception {
        try (var connection = emptyDatabase("reps_and_drops_migration")) {
            var liquibase = liquibase(connection);
            liquibase.update(changesetsBefore(liquibase), "");
            exec(connection, "INSERT INTO sizylift.user_account (id, issuer, subject) VALUES ('%s', 'issuer', '%s')"
                    .formatted(user, user));
            exec(connection, """
                    INSERT INTO sizylift.workout (user_id, id, workout_date, started_at, completed_at, quality, updated_at)
                    VALUES ('%s', '%s', '2026-11-10', now(), now(), 'great', now())""".formatted(user, workout));
            exec(connection, """
                    INSERT INTO sizylift.workout_block (user_id, id, workout_id, position, block_type)
                    VALUES ('%s', '%s', '%s', 0, 'single')""".formatted(user, block, workout));
            var legRaises = exercise(connection, 0, LEG_RAISES, "added_weight_reps");
            var legSet = set(connection, legRaises, "added_weight_reps", 0, "working", null, 12);
            var pushUps = exercise(connection, 1, PUSH_UPS, "added_weight_reps");
            var pushSet = set(connection, pushUps, "added_weight_reps", 0, "working", 10000, 15);
            var bench = exercise(connection, 2, BENCH, "weight_reps");
            var top = set(connection, bench, "weight_reps", 0, "working", 100000, 5);
            var drop1 = set(connection, bench, "weight_reps", 1, "drop", 80000, 6);
            set(connection, bench, "weight_reps", 2, "drop", 60000, 8);
            var next = set(connection, bench, "weight_reps", 3, "working", 90000, 5);
            var lonely = exercise(connection, 3, BENCH, "weight_reps");
            var orphan = set(connection, lonely, "weight_reps", 0, "drop", 50000, 10);
            exec(connection, """
                    INSERT INTO sizylift.workout_record_event (user_id, id, workout_id, workout_exercise_id,
                        actual_set_id, record_type, value, created_at)
                    VALUES ('%s', '%s', '%s', '%s', '%s', 'maximum_weight', 80000, now())"""
                    .formatted(user, UUID.randomUUID(), workout, bench, drop1));

            liquibase.update(new Contexts(), new LabelExpression());

            assertThat(rows(connection, """
                    SELECT id::text || ':' || result_type FROM sizylift.exercise
                    WHERE id IN ('%s', '%s') ORDER BY id""".formatted(PUSH_UPS, LEG_RAISES)))
                    .containsExactly(PUSH_UPS + ":reps", LEG_RAISES + ":reps");
            assertThat(rows(connection, "SELECT result_type FROM sizylift.workout_exercise WHERE id = '%s'"
                    .formatted(legRaises))).containsExactly("reps");
            assertThat(rows(connection, "SELECT result_type FROM sizylift.workout_exercise WHERE id = '%s'"
                    .formatted(pushUps))).containsExactly("added_weight_reps");
            assertThat(rows(connection, "SELECT result_type || ':' || coalesce(weight_grams::text, '-') "
                    + "FROM sizylift.workout_set WHERE id = '%s'".formatted(legSet))).containsExactly("reps:-");
            assertThat(rows(connection, "SELECT result_type FROM sizylift.workout_set WHERE id = '%s'"
                    .formatted(pushSet))).containsExactly("added_weight_reps");
            assertThat(rows(connection, """
                    SELECT id::text || ':' || position || ':' || set_type || ':' || coalesce(drops::text, '-')
                    FROM sizylift.workout_set WHERE workout_exercise_id IN ('%s', '%s')
                    ORDER BY workout_exercise_id = '%s' DESC, position""".formatted(bench, lonely, bench)))
                    .containsExactly(
                            top + ":0:working:[{\"repetitions\": 6, \"weightGrams\": 80000}, "
                                    + "{\"repetitions\": 8, \"weightGrams\": 60000}]",
                            next + ":1:working:-",
                            orphan + ":0:working:-");
            assertThat(rows(connection, "SELECT id::text FROM sizylift.workout_record_event")).isEmpty();
        }
    }

    private UUID exercise(Connection connection, int position, String exerciseId, String type) throws Exception {
        var id = UUID.randomUUID();
        exec(connection, """
                INSERT INTO sizylift.workout_exercise (user_id, id, workout_id, block_id, position, exercise_id,
                    exercise_name, result_type, original_exercise_id, original_exercise_name)
                VALUES ('%s', '%s', '%s', '%s', %d, '%s', 'x', '%s', '%s', 'x')"""
                .formatted(user, id, workout, block, position, exerciseId, type, exerciseId));
        return id;
    }

    private UUID set(Connection connection, UUID exercise, String type, int position, String setType,
                     Integer weight, int reps) throws Exception {
        var id = UUID.randomUUID();
        exec(connection, """
                INSERT INTO sizylift.workout_set (user_id, id, workout_id, workout_exercise_id, position, result_type,
                    set_type, completion_status, weight_grams, repetitions, completed_at)
                VALUES ('%s', '%s', '%s', '%s', %d, '%s', '%s', 'completed', %s, %d, now())"""
                .formatted(user, id, workout, exercise, position, type, setType, weight, reps));
        return id;
    }

    private static void exec(Connection connection, String sql) throws Exception {
        connection.createStatement().execute(sql);
    }

    private static List<String> rows(Connection connection, String sql) throws Exception {
        var result = connection.createStatement().executeQuery(sql);
        var values = new ArrayList<String>();
        while (result.next()) {
            values.add(result.getString(1));
        }
        return values;
    }

    private static int changesetsBefore(Liquibase liquibase) throws Exception {
        var unrun = liquibase.listUnrunChangeSets(new Contexts(), new LabelExpression());
        for (int i = 0; i < unrun.size(); i++) {
            if (unrun.get(i).getFilePath().contains(CHANGESET_FILE)) {
                return i;
            }
        }
        throw new IllegalStateException("Changeset " + CHANGESET_FILE + " not found");
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
