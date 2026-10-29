package com.sizylift.backend.web;

import java.time.Instant;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Класс упражнения в каталоге, %ПМ в плановом подходе и кандидаты для ПМ (docs/coach-workspace.md, разделы 1–3). */
@SpringBootTest
@AutoConfigureMockMvc
class OneRepMaxPlanningApiTest extends PostgresIntegrationTest {

    static final String ISSUER = "http://keycloak.test/realms/sizylift";
    static final Instant T10 = Instant.parse("2026-10-20T10:00:00Z");
    static final UUID SQUAT = UUID.fromString("14000000-0000-4000-8000-000000000001");
    static final UUID FRONT_SQUAT = UUID.fromString("14000000-0000-4000-8000-000000000040");
    static final UUID LATERAL_RAISE = UUID.fromString("14000000-0000-4000-8000-000000000013");
    static final String PERCENT = "targetPercentOneRmTenths";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    String owner;
    String coach;
    String stranger;

    @BeforeEach
    void newUsers() {
        owner = UUID.randomUUID().toString();
        coach = UUID.randomUUID().toString();
        stranger = UUID.randomUUID().toString();
    }

    @Test
    void catalogHasLiftClassAndDerivedIsSbd() throws Exception {
        var catalog = body(call(owner, get("/api/v2/web/catalog")).andExpect(status().isOk()));

        assertThat(exercise(catalog, SQUAT).get("liftClass").asString()).isEqualTo("sbd");
        assertThat(exercise(catalog, SQUAT).get("isSbd").asBoolean()).isTrue();
        assertThat(exercise(catalog, FRONT_SQUAT).get("liftClass").asString()).isEqualTo("basic");
        assertThat(exercise(catalog, FRONT_SQUAT).get("isSbd").asBoolean()).isFalse();
        assertThat(exercise(catalog, LATERAL_RAISE).get("liftClass").asString()).isEqualTo("other");
        assertThat(exercise(catalog, LATERAL_RAISE).get("isSbd").asBoolean()).isFalse();
    }

    @Test
    void plannedSetPercentRoundTripsThroughTheProgram() throws Exception {
        var programId = UUID.randomUUID();
        var program = fixture("program");
        firstPlannedSet(program).put(PERCENT, 725);

        assertThat(push(owner, item("program", programId, null, 0, program)).get("status").asString())
                .isEqualTo("accepted");

        var read = body(call(owner, get("/api/v2/web/programs/" + programId)).andExpect(status().isOk()));
        var set = firstPlannedSet((ObjectNode) read.get("program"));
        assertThat(set.get(PERCENT).asInt()).isEqualTo(725);
        assertThat(set.get("targetWeightGrams").asInt()).as("вес по проценту сервер не пересчитывает")
                .isEqualTo(120000);

        // Id вложенных строк уникальны в пределах пользователя, поэтому телефонная программа — у другого.
        var legacyId = UUID.randomUUID();
        assertThat(legacy(stranger, "program", legacyId, fixture("program")).get("status").asString())
                .isEqualTo("accepted");
        var legacyRead = body(call(stranger, get("/api/v2/web/programs/" + legacyId)).andExpect(status().isOk()));
        assertThat(firstPlannedSet((ObjectNode) legacyRead.get("program")).get(PERCENT).isNull()).isTrue();
    }

    @Test
    void plannedSetPercentOutsideTheRangeIsRejected() throws Exception {
        for (var percent : List.of(0, 1501)) {
            var program = fixture("program");
            firstPlannedSet(program).put(PERCENT, percent);
            var result = push(owner, item("program", UUID.randomUUID(), null, 0, program));
            assertThat(result.get("status").asString()).isEqualTo("rejected");
            assertThat(result.get("error").asString()).isEqualTo("invalid_payload");
        }
        var program = fixture("program");
        firstPlannedSet(program).put(PERCENT, 1500);
        assertThat(push(owner, item("program", UUID.randomUUID(), null, 0, program)).get("status").asString())
                .isEqualTo("accepted");
    }

    @Test
    void standalonePlanKeepsThePercent() throws Exception {
        var workout = (ObjectNode) fixture("program").get("cycles").get(0).get("weeks").get(0).get("workouts")
                .get(0).deepCopy();
        var set = (ObjectNode) workout.get("blocks").get(0).get("exercises").get(0).get("sets").get(0);
        var path = "/api/v2/web/standalone-workouts/" + workout.get("id").asString();

        set.put(PERCENT, 1501);
        call(owner, put(path).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("revision", 0, "workout", workout))))
                .andExpect(status().isBadRequest());

        set.put(PERCENT, 800);
        call(owner, put(path).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("revision", 0, "workout", workout))))
                .andExpect(status().isOk());
        var read = body(call(owner, get(path)).andExpect(status().isOk()));
        assertThat(read.get("workout").get("blocks").get(0).get("exercises").get(0).get("sets").get(0).get(PERCENT)
                .asInt()).isEqualTo(800);
    }

    @Test
    void maxCandidatesAreCompletedHeavySetsOfSbdAndBasicExercisesNewestFirst() throws Exception {
        publishWorkouts();

        var response = body(call(owner, get("/api/v2/web/max-candidates").param("from", "2026-09-28"))
                .andExpect(status().isOk()));

        assertThat(response.get("from").asString()).isEqualTo("2026-09-28");
        assertThat(rows(response.get("sets"))).containsExactly(
                FRONT_SQUAT + " 2026-10-20 60000x5 null",
                FRONT_SQUAT + " 2026-10-20 120000x5 85",
                SQUAT + " 2026-10-14 60000x5 null",
                SQUAT + " 2026-10-14 120000x5 85",
                SQUAT + " 2026-10-14 100000x12 null");
        var first = response.get("sets").get(0);
        assertThat(first.get("workoutId").asString()).isNotBlank();
        assertThat(first.get("workoutDate").asString()).isEqualTo("2026-10-20");

        var later = body(call(owner, get("/api/v2/web/max-candidates").param("from", "2026-10-15"))
                .andExpect(status().isOk()));
        assertThat(later.get("sets").size()).isEqualTo(2);
    }

    @Test
    void maxCandidatesFollowTheReadAccessOfTheWeb() throws Exception {
        publishWorkouts();
        var athleteId = userId(owner).toString();
        var linkId = accept(owner, invite(coach));

        var coachView = body(call(coach, get("/api/v2/web/max-candidates").param("ownerId", athleteId)
                .param("from", "2026-09-28")).andExpect(status().isOk()));
        assertThat(coachView.get("sets").size()).isEqualTo(5);
        call(stranger, get("/api/v2/web/max-candidates").param("ownerId", athleteId).param("from", "2026-09-28"))
                .andExpect(status().isNotFound());
        call(owner, get("/api/v2/web/max-candidates")).andExpect(status().isBadRequest());
        call(owner, get("/api/v2/web/max-candidates").param("from", "28.09.2026")).andExpect(status().isBadRequest());

        call(owner, delete("/api/v2/coaching/links/" + linkId)).andExpect(status().isNoContent());

        call(coach, get("/api/v2/web/max-candidates").param("ownerId", athleteId).param("from", "2026-09-28"))
                .andExpect(status().isNotFound());
    }

    /**
     * Три тренировки: 14.10 — присед (SBD) с подходами, которые не годятся; 20.10 — фронтальный присед (базовое)
     * и махи (прочее); 01.09 — раньше периода.
     */
    private void publishWorkouts() throws Exception {
        var squat = fixture("workout");
        var squatSets = (ArrayNode) squat.get("blocks").get(0).get("exercises").get(0).get("sets");
        squatSets.add(set(squatSets, 100000, 12, "completed"));
        squatSets.add(set(squatSets, 100000, 13, "completed"));
        squatSets.add(set(squatSets, 0, 5, "completed"));
        squatSets.add(set(squatSets, 140000, 3, "pending"));
        legacyAccepted("workout", squat);

        var front = copy(fixture("workout"), "2026-10-20");
        var exercises = front.get("blocks");
        ((ObjectNode) exercises.get(0).get("exercises").get(0)).put("exerciseId", FRONT_SQUAT.toString());
        var raise = (ObjectNode) exercises.get(1).get("exercises").get(0);
        raise.put("exerciseId", LATERAL_RAISE.toString());
        var raiseSet = (ObjectNode) raise.get("sets").get(0);
        raiseSet.put("completionStatus", "completed").put("weightGrams", 10000).put("repetitions", 10)
                .put("completedAt", "2026-10-20T16:30:00.000Z");
        legacyAccepted("workout", front);

        legacyAccepted("workout", copy(fixture("workout"), "2026-09-01"));
    }

    private ObjectNode set(ArrayNode sets, int weightGrams, int repetitions, String completionStatus) {
        var set = (ObjectNode) sets.get(0).deepCopy();
        set.put("id", UUID.randomUUID().toString()).put("position", sets.size()).put("weightGrams", weightGrams)
                .put("repetitions", repetitions).put("completionStatus", completionStatus);
        if (!"completed".equals(completionStatus)) {
            set.putNull("completedAt");
        }
        return set;
    }

    /** Та же тренировка в другой день: новые id, без снимков плана и рекордов. */
    private ObjectNode copy(ObjectNode workout, String date) {
        workout.put("workoutDate", date).putNull("plannedWorkoutId");
        workout.set("planSetSnapshots", json.createArrayNode());
        workout.set("recordEvents", json.createArrayNode());
        newIds(workout);
        return workout;
    }

    private static void newIds(JsonNode node) {
        if (node instanceof ObjectNode object) {
            if (object.has("id")) {
                object.put("id", UUID.randomUUID().toString());
            }
            if (object.has("sourcePlannedSetId")) {
                object.putNull("sourcePlannedSetId");
            }
            object.values().forEach(OneRepMaxPlanningApiTest::newIds);
        } else if (node instanceof ArrayNode array) {
            array.forEach(OneRepMaxPlanningApiTest::newIds);
        }
    }

    private static List<String> rows(JsonNode sets) {
        var rows = new ArrayList<String>();
        sets.forEach(set -> rows.add(set.get("exerciseId").asString() + " " + set.get("workoutDate").asString()
                + " " + set.get("weightGrams").asInt() + "x" + set.get("repetitions").asInt() + " "
                + (set.get("rpeTenths").isNull() ? "null" : set.get("rpeTenths").asInt())));
        return rows;
    }

    private static ObjectNode firstPlannedSet(ObjectNode program) {
        return (ObjectNode) program.get("cycles").get(0).get("weeks").get(0).get("workouts").get(0).get("blocks")
                .get(0).get("exercises").get(0).get("sets").get(0);
    }

    private static JsonNode exercise(JsonNode catalog, UUID id) {
        for (var exercise : catalog.get("exercises")) {
            if (exercise.get("id").asString().equals(id.toString())) {
                return exercise;
            }
        }
        throw new AssertionError("Exercise is not in the catalog");
    }

    private String invite(String subject) throws Exception {
        call(subject, put("/api/v2/coaching/coach-mode").contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}")).andExpect(status().isOk());
        return body(call(subject, post("/api/v2/coaching/invites")).andExpect(status().isCreated()))
                .get("token").asString();
    }

    private UUID accept(String subject, String token) throws Exception {
        var link = body(call(subject, post("/api/v2/coaching/invites/" + token + "/accept"))
                .andExpect(status().isOk()));
        return UUID.fromString(link.get("id").asString());
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

    private void legacyAccepted(String type, JsonNode payload) throws Exception {
        assertThat(legacy(owner, type, UUID.randomUUID(), payload).get("status").asString()).isEqualTo("accepted");
    }

    private JsonNode legacy(String subject, String type, UUID id, JsonNode payload) throws Exception {
        var item = new HashMap<String, Object>();
        item.put("type", type);
        item.put("id", id);
        item.put("version", 1);
        item.put("schemaVersion", 1);
        item.put("deleted", false);
        item.put("payload", payload);
        return body(call(subject, post("/api/v1/publication/batch").contentType(MediaType.APPLICATION_JSON)
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
