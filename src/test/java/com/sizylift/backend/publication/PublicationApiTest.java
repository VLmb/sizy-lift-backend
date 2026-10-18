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
import io.micrometer.core.instrument.MeterRegistry;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Контракт публикации (docs/publication.md) на настоящем PostgreSQL; payload — общие фикстуры v1. */
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

    @Autowired
    MeterRegistry meters;

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
    void recordsPublicationMetrics() throws Exception {
        var accepted = itemCount("workout", "accepted", "none");
        var stale = itemCount("workout", "stale", "none");
        var batches = meters.find("sizylift.publication.batch").timer();
        var batchesBefore = batches == null ? 0 : batches.count();

        publish(item("workout", WORKOUT, 1, fixture("workout")));
        publish(item("workout", WORKOUT, 1, fixture("workout")));

        assertThat(itemCount("workout", "accepted", "none")).isEqualTo(accepted + 1);
        assertThat(itemCount("workout", "stale", "none")).isEqualTo(stale + 1);
        assertThat(meters.get("sizylift.publication.batch").timer().count()).isEqualTo(batchesBefore + 2);
        assertThat(meters.get("sizylift.publication.batch.size").summary().max()).isGreaterThanOrEqualTo(1);
    }

    private double itemCount(String type, String status, String error) {
        var counter = meters.find("sizylift.publication.items")
                .tags("type", type, "status", status, "error", error).counter();
        return counter == null ? 0 : counter.count();
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
    void storesHealthDataWithoutExtraSteps() throws Exception {
        var results = publish(item("injury", UUID.randomUUID(), 1, fixture("injury")),
                item("contraindication", UUID.randomUUID(), 1, fixture("contraindication")));

        assertThat(statuses(results)).containsExactly("accepted", "accepted");
        assertThat(count("injury") + count("contraindication")).isEqualTo(2);
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
