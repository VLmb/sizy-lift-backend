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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Чтение для веба (docs/web.md): свои данные, данные подопечного у тренера, 404 для чужих, CORS. */
@SpringBootTest
@AutoConfigureMockMvc
class WebApiTest extends PostgresIntegrationTest {

    static final String ISSUER = "http://keycloak.test/realms/sizylift";
    static final Instant T10 = Instant.parse("2026-10-20T10:00:00Z");
    static final UUID CATALOG_SQUAT = UUID.fromString("14000000-0000-4000-8000-000000000001");
    static final String ALLOWED_ORIGIN = "http://web.test";

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
    void listsOwnProgramsAndReturnsTheProgramWithItsRevision() throws Exception {
        var programId = UUID.randomUUID();
        publish(owner, "program", programId, 1, fixture("program"));

        var list = body(call(owner, get("/api/v2/web/programs")).andExpect(status().isOk()));
        assertThat(list.get("ownerId").asString()).isEqualTo(userId(owner).toString());
        var summary = list.get("programs").get(0);
        assertThat(summary.get("id").asString()).isEqualTo(programId.toString());
        assertThat(summary.get("name").asString()).isEqualTo("Силовой блок");
        assertThat(summary.get("weeks").asInt()).isEqualTo(1);
        assertThat(summary.get("workouts").asInt()).isEqualTo(1);
        assertThat(summary.get("endDate").asString()).isEqualTo("2026-10-18");
        assertThat(summary.get("authorId").isNull()).isTrue();

        var program = body(call(owner, get("/api/v2/web/programs/" + programId)).andExpect(status().isOk()));
        var revision = program.get("revision").asLong();
        assertThat(revision).isEqualTo(summary.get("revision").asLong());
        assertThat(program.get("program").get("cycles").get(0).get("weeks").get(0).get("workouts").get(0)
                .get("blocks").get(0).get("exercises").get(0).get("sets").get(0).get("targetWeightGrams").asInt())
                .isEqualTo(120000);
    }

    @Test
    void writesThroughSyncWithTheRevisionFromTheWebRead() throws Exception {
        var programId = UUID.randomUUID();
        publish(owner, "program", programId, 1, fixture("program"));
        var read = body(call(owner, get("/api/v2/web/programs/" + programId)).andExpect(status().isOk()));
        var edited = (ObjectNode) read.get("program").deepCopy();
        edited.put("name", "Правка из веба");

        var result = push(owner, item("program", programId, null, read.get("revision").asLong(), 1, edited));

        assertThat(result.get("status").asString()).isEqualTo("accepted");
        var after = body(call(owner, get("/api/v2/web/programs/" + programId)).andExpect(status().isOk()));
        assertThat(after.get("program").get("name").asString()).isEqualTo("Правка из веба");
        assertThat(after.get("revision").asLong()).isEqualTo(result.get("revision").asLong());
    }

    @Test
    void deletedProgramsAreNotListedOrReturned() throws Exception {
        var programId = UUID.randomUUID();
        publish(owner, "program", programId, 1, fixture("program"));
        tombstone(owner, "program", programId, 2);

        var list = body(call(owner, get("/api/v2/web/programs")).andExpect(status().isOk()));
        assertThat(list.get("programs").size()).isZero();
        call(owner, get("/api/v2/web/programs/" + programId)).andExpect(status().isNotFound());
        call(owner, get("/api/v2/web/programs/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    void catalogHasSystemExercisesAndOnlyTheCallersOwn() throws Exception {
        var ownExercise = UUID.randomUUID();
        publish(owner, "exercise", ownExercise, 1, fixture("exercise"));

        var catalog = body(call(owner, get("/api/v2/web/catalog")).andExpect(status().isOk()));
        assertThat(catalog.get("categories").size()).isPositive();
        assertThat(catalog.get("equipment").size()).isPositive();
        assertThat(catalog.get("muscles").size()).isPositive();
        var squat = exercise(catalog, CATALOG_SQUAT);
        assertThat(squat.get("ownerId").isNull()).isTrue();
        assertThat(squat.get("revision").isNull()).isTrue();
        assertThat(squat.get("muscles").size()).isPositive();
        var own = exercise(catalog, ownExercise);
        assertThat(own.get("ownerId").asString()).isEqualTo(userId(owner).toString());
        assertThat(own.get("revision").asLong()).isPositive();
        assertThat(own.get("muscles").get(0).get("role").asString()).isEqualTo("secondary");
        assertThat(own.get("equipmentIds").size()).isEqualTo(1);

        var strangers = body(call(stranger, get("/api/v2/web/catalog")).andExpect(status().isOk()));
        assertThat(ids(strangers.get("exercises"))).contains(CATALOG_SQUAT.toString())
                .doesNotContain(ownExercise.toString());
    }

    @Test
    void coachReadsTheAthleteUntilTheLinkEndsAndStrangersNever() throws Exception {
        var programId = UUID.randomUUID();
        var exerciseId = UUID.randomUUID();
        publish(owner, "program", programId, 1, fixture("program"));
        publish(owner, "exercise", exerciseId, 1, fixture("exercise"));
        var linkId = accept(owner, invite(coach));
        var athleteId = userId(owner).toString();

        var programs = body(call(coach, get("/api/v2/web/programs").param("ownerId", athleteId))
                .andExpect(status().isOk()));
        assertThat(programs.get("ownerId").asString()).isEqualTo(athleteId);
        assertThat(programs.get("programs").size()).isEqualTo(1);
        call(coach, get("/api/v2/web/programs/" + programId).param("ownerId", athleteId))
                .andExpect(status().isOk());
        var catalog = body(call(coach, get("/api/v2/web/catalog").param("ownerId", athleteId))
                .andExpect(status().isOk()));
        assertThat(ids(catalog.get("exercises"))).contains(exerciseId.toString());
        call(stranger, get("/api/v2/web/programs").param("ownerId", athleteId)).andExpect(status().isNotFound());
        call(stranger, get("/api/v2/web/catalog").param("ownerId", athleteId)).andExpect(status().isNotFound());

        call(owner, delete("/api/v2/coaching/links/" + linkId)).andExpect(status().isNoContent());

        call(coach, get("/api/v2/web/programs").param("ownerId", athleteId)).andExpect(status().isNotFound());
        call(coach, get("/api/v2/web/programs/" + programId).param("ownerId", athleteId))
                .andExpect(status().isNotFound());
    }

    @Test
    void returnsOneRepMaxesAndCompletedSetsOfAnExercise() throws Exception {
        publish(owner, "one_rep_max", CATALOG_SQUAT, 1, fixture("one_rep_max"));
        var workoutId = UUID.randomUUID();
        publish(owner, "workout", workoutId, 1, fixture("workout"));

        var maxes = body(call(owner, get("/api/v2/web/one-rep-maxes")).andExpect(status().isOk()));
        var squat = maxes.get("oneRepMaxes").get(0);
        assertThat(squat.get("exerciseId").asString()).isEqualTo(CATALOG_SQUAT.toString());
        assertThat(squat.get("weightGrams").asInt()).isEqualTo(160000);
        assertThat(squat.get("revision").asLong()).isPositive();

        var history = body(call(owner, get("/api/v2/web/exercises/" + CATALOG_SQUAT + "/history"))
                .andExpect(status().isOk()));
        var entry = history.get("entries").get(0);
        assertThat(entry.get("workoutId").asString()).isEqualTo(workoutId.toString());
        assertThat(entry.get("workoutDate").asString()).isEqualTo("2026-10-14");
        var completed = jdbc.queryForObject("""
                SELECT count(*) FROM sizylift.workout_set s
                JOIN sizylift.workout_exercise we ON we.user_id = s.user_id AND we.id = s.workout_exercise_id
                WHERE s.user_id = ? AND s.workout_id = ? AND we.exercise_id = ? AND s.completion_status = 'completed'
                """, Integer.class, userId(owner), workoutId, CATALOG_SQUAT);
        assertThat(entry.get("sets").size()).isEqualTo(completed).isPositive();
        assertThat(entry.get("sets").get(0).get("setType").asString()).isEqualTo("warmup");

        call(owner, get("/api/v2/web/exercises/" + CATALOG_SQUAT + "/history").param("limit", "0"))
                .andExpect(status().isBadRequest());
        call(stranger, get("/api/v2/web/exercises/" + CATALOG_SQUAT + "/history")
                .param("ownerId", userId(owner).toString())).andExpect(status().isNotFound());
    }

    @Test
    void requiresAToken() throws Exception {
        mvc.perform(get("/api/v2/web/programs")).andExpect(status().isUnauthorized());
    }

    @Test
    void allowsTheConfiguredWebOriginOnly() throws Exception {
        mvc.perform(options("/api/v2/web/programs")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, HttpHeaders.AUTHORIZATION))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN));

        mvc.perform(options("/api/v2/web/programs")
                        .header(HttpHeaders.ORIGIN, "http://evil.test")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    private static JsonNode exercise(JsonNode catalog, UUID id) {
        for (var exercise : catalog.get("exercises")) {
            if (exercise.get("id").asString().equals(id.toString())) {
                return exercise;
            }
        }
        throw new AssertionError("No exercise " + id);
    }

    private static List<String> ids(JsonNode array) {
        var result = new ArrayList<String>();
        array.forEach(node -> result.add(node.get("id").asString()));
        return result;
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

    private Map<String, Object> item(String type, UUID id, UUID ownerId, long baseRevision, long clientVersion,
                                     JsonNode payload) {
        var item = new HashMap<String, Object>();
        item.put("type", type);
        item.put("id", id);
        item.put("ownerId", ownerId);
        item.put("baseRevision", baseRevision);
        item.put("changedAt", T10.plusSeconds(clientVersion).toString());
        item.put("clientId", "web-" + UUID.randomUUID());
        item.put("clientVersion", clientVersion);
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

    private void publish(String subject, String type, UUID id, long version, JsonNode payload) throws Exception {
        assertThat(legacy(subject, type, id, version, false, payload).get("status").asString())
                .isEqualTo("accepted");
    }

    private void tombstone(String subject, String type, UUID id, long version) throws Exception {
        assertThat(legacy(subject, type, id, version, true, null).get("status").asString()).isEqualTo("accepted");
    }

    private JsonNode legacy(String subject, String type, UUID id, long version, boolean deleted, JsonNode payload)
            throws Exception {
        var item = new HashMap<String, Object>();
        item.put("type", type);
        item.put("id", id);
        item.put("version", version);
        item.put("schemaVersion", 1);
        item.put("deleted", deleted);
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
