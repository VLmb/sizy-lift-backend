package com.sizylift.backend.publication;

import java.util.List;
import java.util.Map;

import com.sizylift.backend.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The system exercise catalog must match the app's seed exactly: workouts and plans refer to it by UUID.
 * The fixture is produced by the app's tests/exercise-catalog-contract.test.ts (VLmb/sizy-lift-app).
 */
@SpringBootTest
class ExerciseCatalogContractTest extends PostgresIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    @Test
    void catalogMatchesTheAppSeed() throws Exception {
        JsonNode catalog;
        try (var input = new ClassPathResource("catalog/exercise-catalog.json").getInputStream()) {
            catalog = json.readTree(input);
        }

        assertThat(references("movement_category")).isEqualTo(fixtureReferences(catalog.get("movementCategories")));
        assertThat(references("equipment")).isEqualTo(fixtureReferences(catalog.get("equipment")));
        assertThat(references("muscle_group")).isEqualTo(fixtureReferences(catalog.get("muscleGroups")));

        var exercises = jdbc.queryForList("""
                SELECT e.id::text AS id, e.name, e.short_name, e.movement_category_id::text AS category,
                       e.result_type, e.is_sbd, e.note,
                       (SELECT string_agg(m.muscle_group_id::text || ':' || m.role, ',' ORDER BY m.role, m.muscle_group_id)
                        FROM sizylift.exercise_muscle m WHERE m.exercise_id = e.id) AS muscles,
                       (SELECT string_agg(q.equipment_id::text, ',' ORDER BY q.equipment_id)
                        FROM sizylift.exercise_equipment q WHERE q.exercise_id = e.id) AS equipment
                FROM sizylift.exercise e WHERE e.user_id IS NULL ORDER BY e.id
                """);
        assertThat(exercises).hasSize(catalog.get("exercises").size());
        for (int i = 0; i < exercises.size(); i++) {
            var row = exercises.get(i);
            var expected = catalog.get("exercises").get(i);
            assertThat(row.get("id")).isEqualTo(expected.get("id").asString());
            assertThat(row.get("name")).isEqualTo(expected.get("name").asString());
            assertThat(row.get("short_name")).isEqualTo(text(expected.get("shortName")));
            assertThat(row.get("category")).isEqualTo(text(expected.get("movementCategoryId")));
            assertThat(row.get("result_type")).isEqualTo(expected.get("resultType").asString());
            assertThat(row.get("is_sbd")).isEqualTo(expected.get("isSbd").asBoolean());
            assertThat(row.get("note")).isEqualTo(text(expected.get("note")));
            assertThat(row.get("muscles")).isEqualTo(joined(expected.get("muscles").valueStream()
                    .map(m -> m.get("muscleGroupId").asString() + ":" + m.get("role").asString())
                    .sorted((a, b) -> a.split(":")[1].compareTo(b.split(":")[1]) != 0
                            ? a.split(":")[1].compareTo(b.split(":")[1]) : a.compareTo(b))
                    .toList()));
            assertThat(row.get("equipment")).isEqualTo(joined(expected.get("equipmentIds").valueStream()
                    .map(JsonNode::asString).sorted().toList()));
        }
    }

    private List<Map<String, Object>> references(String table) {
        return jdbc.queryForList("SELECT id::text AS id, code, name FROM sizylift." + table + " ORDER BY id");
    }

    private static List<Map<String, Object>> fixtureReferences(JsonNode items) {
        return items.valueStream()
                .map(item -> Map.<String, Object>of("id", item.get("id").asString(),
                        "code", item.get("code").asString(), "name", item.get("name").asString()))
                .toList();
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asString();
    }

    private static String joined(List<String> values) {
        return values.isEmpty() ? null : String.join(",", values);
    }
}
