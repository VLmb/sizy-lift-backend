package com.sizylift.backend.publication;

import java.time.Instant;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Класс упражнения в payload v1 и совместимость с {@code isSbd} телефона (docs/coach-workspace.md, раздел 1). */
@SpringBootTest
@AutoConfigureMockMvc
class ExerciseLiftClassApiTest extends PostgresIntegrationTest {

    static final String ISSUER = "http://keycloak.test/realms/sizylift";
    static final Instant T10 = Instant.parse("2026-10-20T10:00:00Z");
    static final long HOUR_SECONDS = 3600;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    String owner;
    UUID exerciseId;

    @BeforeEach
    void newUser() {
        owner = UUID.randomUUID().toString();
        exerciseId = UUID.randomUUID();
    }

    @Test
    void phoneWithoutLiftClassMapsIsSbdAndKeepsANonSbdClass() throws Exception {
        assertThat(legacy(1, exercise(true, null))).isEqualTo("accepted");
        assertThat(storedClass()).isEqualTo("sbd");

        assertThat(legacy(2, exercise(false, null))).isEqualTo("accepted");
        assertThat(storedClass()).as("снятый isSbd у sbd даёт other").isEqualTo("other");

        assertThat(push(exercise(false, "basic"), revision()).get("status").asString()).isEqualTo("accepted");
        assertThat(storedClass()).isEqualTo("basic");

        assertThat(legacy(3, exercise(false, null))).isEqualTo("accepted");
        assertThat(storedClass()).as("isSbd = false не трогает basic").isEqualTo("basic");

        assertThat(legacy(4, exercise(true, null))).isEqualTo("accepted");
        assertThat(storedClass()).isEqualTo("sbd");
    }

    @Test
    void newExerciseWithoutLiftClassAndIsSbdIsOther() throws Exception {
        assertThat(legacy(1, exercise(false, null))).isEqualTo("accepted");
        assertThat(storedClass()).isEqualTo("other");
    }

    @Test
    void explicitLiftClassWinsOverIsSbdAndIsSbdIsDerivedOnRead() throws Exception {
        assertThat(push(exercise(true, "basic"), 0).get("status").asString()).isEqualTo("accepted");
        assertThat(storedClass()).isEqualTo("basic");

        var fed = feedPayload();
        assertThat(fed.get("liftClass").asString()).isEqualTo("basic");
        assertThat(fed.get("isSbd").asBoolean()).isFalse();
        var catalog = catalogExercise();
        assertThat(catalog.get("liftClass").asString()).isEqualTo("basic");
        assertThat(catalog.get("isSbd").asBoolean()).isFalse();

        assertThat(push(exercise(false, "sbd"), revision()).get("status").asString()).isEqualTo("accepted");
        assertThat(feedPayload().get("isSbd").asBoolean()).isTrue();
        assertThat(catalogExercise().get("isSbd").asBoolean()).isTrue();
    }

    @Test
    void sbdOrBasicForAnotherResultTypeIsRejected() throws Exception {
        for (var liftClass : List.of("sbd", "basic")) {
            var payload = exercise(false, liftClass).put("resultType", "added_weight_reps");
            assertThat(legacyResult(1, payload).get("error").asString()).isEqualTo("invalid_payload");
            var pushed = push(payload, 0);
            assertThat(pushed.get("status").asString()).isEqualTo("rejected");
            assertThat(pushed.get("error").asString()).isEqualTo("invalid_payload");
        }
        assertThat(legacyResult(1, exercise(false, "unknown")).get("error").asString()).isEqualTo("invalid_payload");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sizylift.exercise WHERE id = ?", Integer.class,
                exerciseId)).isZero();
    }

    @Test
    void phoneIsSbdForAnotherResultTypeStaysOther() throws Exception {
        var payload = exercise(true, null).put("resultType", "duration");

        assertThat(legacy(1, payload)).isEqualTo("accepted");

        assertThat(storedClass()).isEqualTo("other");
    }

    @Test
    void coachExerciseCopyKeepsItsLiftClass() throws Exception {
        var coach = UUID.randomUUID().toString();
        var athleteId = userId(owner);
        call(coach, put("/api/v2/coaching/coach-mode").contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}")).andExpect(status().isOk());
        var token = body(call(coach, post("/api/v2/coaching/invites")).andExpect(status().isCreated()))
                .get("token").asString();
        call(owner, post("/api/v2/coaching/invites/" + token + "/accept")).andExpect(status().isOk());
        var coachExercise = UUID.randomUUID();
        assertThat(push(coach, item("exercise", coachExercise, null, 0, exercise(false, "basic"))).get("status")
                .asString()).isEqualTo("accepted");

        var program = fixture("program");
        var exercises = (ArrayNode) program.get("cycles").get(0).get("weeks").get(0).get("workouts").get(0)
                .get("blocks").get(0).get("exercises");
        ((ObjectNode) exercises.get(0)).put("exerciseId", coachExercise.toString());
        var programId = UUID.randomUUID();
        assertThat(push(coach, item("program", programId, athleteId, 0, program)).get("status").asString())
                .isEqualTo("accepted");

        var copy = jdbc.queryForObject("SELECT exercise_id FROM sizylift.planned_exercise WHERE user_id = ? "
                + "AND program_id = ?", UUID.class, athleteId, programId);
        assertThat(copy).isNotEqualTo(coachExercise);
        assertThat(jdbc.queryForObject("SELECT lift_class FROM sizylift.exercise WHERE user_id = ? AND id = ?",
                String.class, athleteId, copy)).isEqualTo("basic");
    }

    private ObjectNode exercise(boolean isSbd, String liftClass) throws Exception {
        var payload = fixture("exercise");
        payload.put("isSbd", isSbd);
        if (liftClass != null) {
            payload.put("liftClass", liftClass);
        }
        return payload;
    }

    private String storedClass() throws Exception {
        return jdbc.queryForObject("SELECT lift_class FROM sizylift.exercise WHERE user_id = ? AND id = ?",
                String.class, userId(owner), exerciseId);
    }

    private long revision() throws Exception {
        return jdbc.queryForObject("SELECT revision FROM sizylift.published_aggregate WHERE user_id = ? "
                + "AND type = 'exercise' AND aggregate_id = ?", Long.class, userId(owner), exerciseId);
    }

    private JsonNode feedPayload() throws Exception {
        var changes = body(call(owner, get("/api/v2/sync/changes").param("after", "0").param("limit", "100"))
                .andExpect(status().isOk())).get("changes");
        for (var change : changes) {
            if (change.get("id").asString().equals(exerciseId.toString())) {
                return change.get("payload");
            }
        }
        throw new AssertionError("Exercise is not in the feed");
    }

    private JsonNode catalogExercise() throws Exception {
        var exercises = body(call(owner, get("/api/v2/web/catalog")).andExpect(status().isOk())).get("exercises");
        for (var exercise : exercises) {
            if (exercise.get("id").asString().equals(exerciseId.toString())) {
                return exercise;
            }
        }
        throw new AssertionError("Exercise is not in the catalog");
    }

    private String legacy(long version, JsonNode payload) throws Exception {
        return legacyResult(version, payload).get("status").asString();
    }

    private JsonNode legacyResult(long version, JsonNode payload) throws Exception {
        var item = new HashMap<String, Object>();
        item.put("type", "exercise");
        item.put("id", exerciseId);
        item.put("version", version);
        item.put("schemaVersion", 1);
        item.put("deleted", false);
        // Позже правки через sync (T10): иначе запись v1 проиграла бы её по updatedAt.
        item.put("payload", ((ObjectNode) payload.deepCopy()).put("updatedAt",
                T10.plusSeconds(HOUR_SECONDS * version).toString()));
        return body(call(owner, post("/api/v1/publication/batch").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("items", List.of(item))))).andExpect(status().isOk()))
                .get("results").get(0);
    }

    private JsonNode push(JsonNode payload, long baseRevision) throws Exception {
        return push(owner, item("exercise", exerciseId, null, baseRevision, payload));
    }

    private Map<String, Object> item(String type, UUID id, UUID ownerId, long baseRevision, JsonNode payload) {
        var item = new HashMap<String, Object>();
        item.put("type", type);
        item.put("id", id);
        item.put("ownerId", ownerId);
        item.put("baseRevision", baseRevision);
        item.put("changedAt", T10.toString());
        item.put("clientId", "web-" + UUID.randomUUID());
        item.put("clientVersion", 1);
        item.put("schemaVersion", 1);
        item.put("deleted", false);
        item.put("payload", payload);
        return item;
    }

    private JsonNode push(String subject, Map<String, Object> item) throws Exception {
        return body(call(subject, post("/api/v2/sync/push").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("items", List.of(item))))).andExpect(status().isOk()))
                .get("results").get(0);
    }

    private ObjectNode fixture(String name) throws Exception {
        try (var input = new ClassPathResource("publication/" + name + ".v1.json").getInputStream()) {
            return (ObjectNode) json.readTree(input);
        }
    }

    private ResultActions call(String subject, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.with(token(subject)));
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private UUID userId(String subject) throws Exception {
        call(subject, get("/api/v1/me")).andExpect(status().isOk());
        return jdbc.queryForObject("SELECT id FROM sizylift.user_account WHERE issuer = ? AND subject = ?",
                UUID.class, ISSUER, subject);
    }

    private static JwtRequestPostProcessor token(String subject) {
        return jwt().jwt(token -> token.issuer(ISSUER).subject(subject).claim("email", subject + "@example.com"));
    }
}
