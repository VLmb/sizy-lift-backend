package com.sizylift.backend.publication;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.sizylift.backend.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Publication contract (docs/publication.md) against real PostgreSQL. Payloads are the shared v1 fixtures. */
@SpringBootTest
@AutoConfigureMockMvc
class PublicationApiTest extends PostgresIntegrationTest {

    static final String ISSUER = "http://keycloak.test/realms/sizylift";
    static final UUID WORKOUT = UUID.fromString("20000000-0000-4000-8000-000000000001");
    static final UUID PROGRAM = UUID.fromString("30000000-0000-4000-8000-000000000001");
    static final UUID OWN_EXERCISE = UUID.fromString("21000000-0000-4000-8000-000000000001");
    static final UUID SQUAT = UUID.fromString("14000000-0000-4000-8000-000000000001");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    String subject;

    @BeforeEach
    void newUser() {
        subject = UUID.randomUUID().toString();
    }

    @Test
    void storesACompletedWorkoutRelationally() throws Exception {
        var results = publish(item("workout", WORKOUT, 1, fixture("workout")));

        assertThat(statuses(results)).containsExactly("accepted");
        assertThat(count("workout")).isEqualTo(1);
        assertThat(count("workout_block")).isEqualTo(2);
        assertThat(count("workout_exercise")).isEqualTo(2);
        assertThat(count("workout_set")).isEqualTo(3);
        assertThat(count("workout_plan_set_snapshot")).isEqualTo(1);
        assertThat(count("workout_record_event")).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT max(s.weight_grams) FROM sizylift.workout_set s
                JOIN sizylift.workout_exercise e ON e.user_id = s.user_id AND e.id = s.workout_exercise_id
                WHERE s.user_id = ? AND e.exercise_id = ? AND s.completion_status = 'completed'
                """, Integer.class, userId(), SQUAT)).isEqualTo(120000);
    }

    @Test
    void repeatedDeliveryOfTheSameVersionChangesNothing() throws Exception {
        publish(item("workout", WORKOUT, 3, fixture("workout")));

        var repeated = publish(item("workout", WORKOUT, 3, fixture("workout")));

        assertThat(statuses(repeated)).containsExactly("stale");
        assertThat(count("workout_set")).isEqualTo(3);
    }

    @Test
    void newerVersionReplacesTheWholeWorkoutAndOlderOneIsIgnored() throws Exception {
        publish(item("workout", WORKOUT, 1, fixture("workout")));
        var edited = fixture("workout");
        ((ObjectNode) edited.get("blocks").get(0).get("exercises").get(0)).putArray("sets");
        edited.put("title", "Только присед");

        assertThat(statuses(publish(item("workout", WORKOUT, 2, edited)))).containsExactly("accepted");
        assertThat(count("workout_set")).isEqualTo(1);

        assertThat(statuses(publish(item("workout", WORKOUT, 1, fixture("workout"))))).containsExactly("stale");
        assertThat(count("workout_set")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT title FROM sizylift.workout WHERE user_id = ?", String.class,
                userId())).isEqualTo("Только присед");
    }

    @Test
    void tombstoneDeletesAndKeepsAnOlderCopyFromComingBack() throws Exception {
        publish(item("workout", WORKOUT, 4, fixture("workout")));

        assertThat(statuses(publish(tombstone("workout", WORKOUT, 5)))).containsExactly("accepted");
        assertThat(count("workout")).isZero();
        assertThat(count("workout_set")).isZero();

        assertThat(statuses(publish(item("workout", WORKOUT, 4, fixture("workout"))))).containsExactly("stale");
        assertThat(count("workout")).isZero();
    }

    @Test
    void invalidItemIsRejectedWithoutAffectingTheRestOfTheBatch() throws Exception {
        var invalid = fixture("workout");
        ((ObjectNode) invalid.get("blocks").get(0).get("exercises").get(0).get("sets").get(1))
                .put("rpeTenths", 87);

        var results = publish(
                item("workout", WORKOUT, 1, invalid),
                item("program", PROGRAM, 1, fixture("program")),
                item("workout", UUID.randomUUID(), 1, json.createObjectNode()));

        assertThat(statuses(results)).containsExactly("rejected", "accepted", "rejected");
        assertThat(results.get(0).get("error").asString()).isEqualTo("invalid_payload");
        assertThat(count("workout")).isZero();
        assertThat(count("workout_set")).isZero();
        assertThat(count("planned_set")).isEqualTo(1);
    }

    @Test
    void unknownTypeAndSchemaAreRejected() throws Exception {
        var unknownSchema = item("workout", WORKOUT, 1, fixture("workout"));
        unknownSchema.put("schemaVersion", 2);

        var results = publish(item("photo", UUID.randomUUID(), 1, json.createObjectNode()), unknownSchema);

        assertThat(results.valueStream().map(r -> r.get("error").asString()))
                .containsExactly("unsupported_type", "unsupported_schema");
    }

    @Test
    void usersAreIsolatedEvenWithTheSameIds() throws Exception {
        publish(item("workout", WORKOUT, 1, fixture("workout")));
        var owner = userId();

        subject = UUID.randomUUID().toString();
        assertThat(statuses(publish(item("workout", WORKOUT, 1, fixture("workout"))))).containsExactly("accepted");
        publish(tombstone("workout", WORKOUT, 2));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM sizylift.workout WHERE user_id = ?", Long.class, owner))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sizylift.workout_set WHERE user_id = ?", Long.class,
                owner)).isEqualTo(3);
    }

    @Test
    void ownExerciseIsStoredAndCatalogIdsCannotBeTaken() throws Exception {
        var results = publish(
                item("exercise", OWN_EXERCISE, 1, fixture("exercise")),
                item("exercise", SQUAT, 1, fixture("exercise")));

        assertThat(statuses(results)).containsExactly("accepted", "rejected");
        assertThat(results.get(1).get("error").asString()).isEqualTo("id_conflict");
        assertThat(jdbc.queryForObject("SELECT name FROM sizylift.exercise WHERE id = ?", String.class, SQUAT))
                .isEqualTo("Присед со штангой");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sizylift.exercise_equipment WHERE exercise_id = ?",
                Long.class, OWN_EXERCISE)).isEqualTo(1);
    }

    @Test
    void storesProfileAndOneRepMax() throws Exception {
        var results = publish(
                item("athlete_profile", UUID.randomUUID(), 1, fixture("athlete_profile")),
                item("one_rep_max", SQUAT, 1, fixture("one_rep_max")));

        assertThat(statuses(results)).containsExactly("accepted", "accepted");
        assertThat(jdbc.queryForObject("SELECT sport_directions[1] FROM sizylift.athlete_profile WHERE user_id = ?",
                String.class, userId())).isEqualTo("powerlifting");
        assertThat(jdbc.queryForObject("SELECT weight_grams FROM sizylift.one_rep_max WHERE user_id = ?",
                Integer.class, userId())).isEqualTo(160000);
    }

    @Test
    void healthDataNeedsSeparateConsent() throws Exception {
        var injury = UUID.randomUUID();
        assertThat(statuses(publish(item("injury", injury, 1, fixture("injury")),
                item("contraindication", UUID.randomUUID(), 1, fixture("contraindication")))))
                .containsExactly("out_of_scope", "out_of_scope");
        assertThat(count("injury")).isZero();

        putScope(scope(true, true, null, "all", true, true, true));
        assertThat(statuses(publish(item("injury", injury, 1, fixture("injury"))))).containsExactly("accepted");
        assertThat(count("injury")).isEqualTo(1);

        putScope(scope(true, true, null, "all", true, true, false));
        assertThat(count("injury")).isZero();
    }

    @Test
    void narrowingThePeriodDeletesOlderWorkoutsAndWideningAcceptsThemAgain() throws Exception {
        publish(item("workout", WORKOUT, 7, fixture("workout")));

        putScope(scope(true, true, "2026-10-15", "all", true, true, false));
        assertThat(count("workout")).isZero();
        assertThat(statuses(publish(item("workout", WORKOUT, 7, fixture("workout")))))
                .containsExactly("out_of_scope");

        putScope(scope(true, true, null, "all", true, true, false));
        assertThat(statuses(publish(item("workout", WORKOUT, 7, fixture("workout"))))).containsExactly("accepted");
    }

    @Test
    void activeProgramScopeKeepsOnlyTheActiveProgram() throws Exception {
        var draft = withNewIds(fixture("program"));
        draft.put("status", "draft");
        publish(item("program", PROGRAM, 1, fixture("program")), item("program", UUID.randomUUID(), 1, draft));
        assertThat(count("program")).isEqualTo(2);

        putScope(scope(true, true, null, "active", true, true, false));
        assertThat(count("program")).isEqualTo(1);

        var deactivated = fixture("program");
        deactivated.put("status", "draft");
        assertThat(statuses(publish(item("program", PROGRAM, 2, deactivated)))).containsExactly("out_of_scope");
        assertThat(count("program")).isZero();
    }

    @Test
    void defaultScopeKeepsHealthDataOff() throws Exception {
        mvc.perform(get("/api/v1/publication/scope").with(token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.programs").value("all"))
                .andExpect(jsonPath("$.health").value(false));
    }

    @Test
    void deletingDataRemovesEverythingAndStopsAccepting() throws Exception {
        publish(item("workout", WORKOUT, 1, fixture("workout")), item("program", PROGRAM, 1, fixture("program")),
                item("exercise", OWN_EXERCISE, 1, fixture("exercise")));

        mvc.perform(delete("/api/v1/publication/data").with(token())).andExpect(status().isNoContent());

        assertThat(count("workout") + count("program") + count("exercise")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sizylift.published_aggregate WHERE user_id = ?",
                Long.class, userId())).isZero();
        assertThat(statuses(publish(item("workout", WORKOUT, 2, fixture("workout")))))
                .containsExactly("out_of_scope");
        mvc.perform(get("/api/v1/publication/scope").with(token())).andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void rejectsOversizedBatchesAndAnonymousRequests() throws Exception {
        var items = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 101; i++) {
            items.add(tombstone("workout", UUID.randomUUID(), 1));
        }
        mvc.perform(post("/api/v1/publication/batch").with(token()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("items", items)))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/publication/batch").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    private JsonNode publish(Map<?, ?>... items) throws Exception {
        var body = mvc.perform(post("/api/v1/publication/batch").with(token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("items", List.of(items)))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("results");
    }

    private void putScope(Map<String, Object> scope) throws Exception {
        mvc.perform(put("/api/v1/publication/scope").with(token()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(scope))).andExpect(status().isOk());
    }

    private static Map<String, Object> scope(boolean enabled, boolean workouts, String since, String programs,
                                             boolean profile, boolean records, boolean health) {
        var scope = new HashMap<String, Object>();
        scope.put("enabled", enabled);
        scope.put("workouts", workouts);
        scope.put("workoutsSince", since);
        scope.put("programs", programs);
        scope.put("profile", profile);
        scope.put("records", records);
        scope.put("health", health);
        return scope;
    }

    private Map<String, Object> item(String type, UUID id, long version, JsonNode payload) {
        var item = tombstone(type, id, version);
        item.put("deleted", false);
        item.put("payload", payload);
        return item;
    }

    private static Map<String, Object> tombstone(String type, UUID id, long version) {
        var item = new HashMap<String, Object>();
        item.put("type", type);
        item.put("id", id);
        item.put("version", version);
        item.put("schemaVersion", 1);
        item.put("deleted", true);
        return item;
    }

    private ObjectNode fixture(String name) throws Exception {
        try (var input = new ClassPathResource("publication/" + name + ".v1.json").getInputStream()) {
            return (ObjectNode) json.readTree(input);
        }
    }

    /** Gives every nested row a fresh id, as a different aggregate from the app would have. */
    private static ObjectNode withNewIds(ObjectNode node) {
        if (node.has("id")) {
            node.put("id", UUID.randomUUID().toString());
        }
        for (var field : node.properties()) {
            if (field.getValue().isArray()) {
                field.getValue().valueStream().filter(JsonNode::isObject)
                        .forEach(child -> withNewIds((ObjectNode) child));
            }
        }
        return node;
    }

    private static List<String> statuses(JsonNode results) {
        return results.valueStream().map(result -> result.get("status").asString()).toList();
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM sizylift." + table + " WHERE user_id = ?", Long.class,
                userId());
    }

    private UUID userId() {
        return jdbc.queryForObject("SELECT id FROM sizylift.user_account WHERE issuer = ? AND subject = ?",
                UUID.class, ISSUER, subject);
    }

    private JwtRequestPostProcessor token() {
        return jwt().jwt(token -> token.issuer(ISSUER).subject(subject).claim("email", subject + "@example.com"));
    }
}
