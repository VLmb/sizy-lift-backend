package com.sizylift.backend.sync;

import java.time.Instant;
import java.time.LocalDate;
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
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Разовые тренировки в sync v2 (ADR-0011): запись владельцем и тренером, лента, push, перенос, «Входящие». */
@SpringBootTest
@AutoConfigureMockMvc
class StandaloneWorkoutSyncApiTest extends PostgresIntegrationTest {

    static final String ISSUER = "http://keycloak.test/realms/sizylift";
    static final Instant T10 = Instant.parse("2026-10-20T10:00:00Z");
    static final String TYPE = "standalone_workout";
    static final LocalDate PLANNED_DATE = LocalDate.parse("2026-10-14");
    static final LocalDate MOVED_TO = LocalDate.parse("2026-10-15");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    String coach;
    String athlete;
    String stranger;
    UUID athleteId;
    UUID coachId;

    @BeforeEach
    void linkedPair() throws Exception {
        coach = UUID.randomUUID().toString();
        athlete = UUID.randomUUID().toString();
        stranger = UUID.randomUUID().toString();
        link(coach, athlete);
        athleteId = userId(athlete);
        coachId = userId(coach);
    }

    @Test
    void ownerWritesAStandaloneWorkoutAndReadsItFromTheFeed() throws Exception {
        var id = UUID.randomUUID();
        var cursor = cursor(athlete);

        var result = push(athlete, item(TYPE, id, null, 0, 1, standalone(id, PLANNED_DATE)));

        assertThat(result.get("status").asString()).isEqualTo("accepted");
        var feed = changes(athlete, cursor);
        assertThat(kinds(feed)).containsExactly("standalone_workout:upsert");
        var payload = feed.get(0).get("payload");
        assertThat(payload.get("workout").get("id").asString()).isEqualTo(id.toString());
        assertThat(payload.get("workout").get("workoutDate").asString()).isEqualTo(PLANNED_DATE.toString());
        assertThat(payload.get("workout").get("blocks").get(0).get("exercises").get(0).get("sets").get(0)
                .get("targetRpeAuto").asBoolean()).isTrue();
        assertThat(feed.get(0).get("authorId").isNull()).isTrue();
        assertThat(kinds(changes(coach, 0))).contains("standalone_workout:upsert");
    }

    @Test
    void theWorkoutIdMustBeTheAggregateIdAndNotAProgramWorkout() throws Exception {
        var id = UUID.randomUUID();
        assertThat(push(athlete, item(TYPE, UUID.randomUUID(), null, 0, 1, standalone(id, PLANNED_DATE)))
                .get("error").asString()).isEqualTo("invalid_payload");
        var program = UUID.randomUUID();
        push(athlete, item("program", program, null, 0, 1, fixture("program")));
        var programWorkout = UUID.fromString(fixture("program").get("cycles").get(0).get("weeks").get(0)
                .get("workouts").get(0).get("id").asString());
        assertThat(push(athlete, item(TYPE, programWorkout, null, 0, 1, standalone(programWorkout, PLANNED_DATE)))
                .get("error").asString()).isEqualTo("id_conflict");
    }

    @Test
    void coachPlansAWorkoutForTheAthleteWhoOnlyReadsIt() throws Exception {
        var id = UUID.randomUUID();
        var cursor = cursor(athlete);

        var result = push(coach, item(TYPE, id, athleteId, 0, 1, standalone(id, PLANNED_DATE)));

        assertThat(result.get("status").asString()).isEqualTo("accepted");
        var feed = changes(athlete, cursor);
        assertThat(kinds(feed)).containsExactly("standalone_workout:upsert");
        assertThat(feed.get(0).get("authorId").asString()).isEqualTo(coachId.toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sizylift.notification_outbox WHERE user_id = ? "
                + "AND kind = 'workout_planned'", Long.class, athleteId)).isEqualTo(1);

        var edited = standalone(id, PLANNED_DATE);
        ((ObjectNode) edited.get("workout")).put("title", "Своя правка");
        assertThat(push(athlete, item(TYPE, id, null, revision(athleteId, id), 2, edited)).get("error")
                .asString()).isEqualTo("forbidden");
        assertThat(push(stranger, item(TYPE, UUID.randomUUID(), athleteId, 0, 1,
                standalone(UUID.randomUUID(), PLANNED_DATE))).get("error").asString()).isEqualTo("forbidden");
    }

    @Test
    void athleteReschedulesTheCoachStandaloneWorkout() throws Exception {
        var id = UUID.randomUUID();
        push(coach, item(TYPE, id, athleteId, 0, 1, standalone(id, PLANNED_DATE)));

        var moved = push(athlete, reschedule(id, 1, MOVED_TO));

        assertThat(moved.get("status").asString()).isEqualTo("accepted");
        assertThat(rescheduleExists(id)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sizylift.coach_event WHERE kind = "
                + "'workout_rescheduled' AND subject_id = ?", Long.class, id)).isEqualTo(1);

        var back = push(athlete, reschedule(id, 2, PLANNED_DATE));
        assertThat(back.get("payloadRewritten").asBoolean()).as("перенос на исходную дату снят").isTrue();
        assertThat(rescheduleExists(id)).isFalse();
    }

    @Test
    void coachChangingTheDateOrDeletingRemovesTheReschedule() throws Exception {
        var id = UUID.randomUUID();
        push(coach, item(TYPE, id, athleteId, 0, 1, standalone(id, PLANNED_DATE)));
        push(athlete, reschedule(id, 1, MOVED_TO));

        push(coach, item(TYPE, id, athleteId, revision(athleteId, id, TYPE), 2,
                standalone(id, LocalDate.parse("2026-10-17"))));
        assertThat(rescheduleExists(id)).isFalse();

        push(athlete, reschedule(id, 2, MOVED_TO));
        assertThat(rescheduleExists(id)).isTrue();
        var athleteCursor = cursor(athlete);
        var tombstone = item(TYPE, id, athleteId, revision(athleteId, id, TYPE), 3, null);
        tombstone.put("deleted", true);
        assertThat(push(coach, tombstone).get("status").asString()).isEqualTo("accepted");
        assertThat(rescheduleExists(id)).isFalse();
        assertThat(kinds(changes(athlete, athleteCursor)))
                .containsExactlyInAnyOrder("workout_reschedule:delete", "standalone_workout:delete");
    }

    private ObjectNode standalone(UUID id, LocalDate date) throws Exception {
        var workout = (ObjectNode) fixture("program").get("cycles").get(0).get("weeks").get(0).get("workouts")
                .get(0).deepCopy();
        workout.put("id", id.toString()).put("workoutDate", date.toString());
        return json.createObjectNode().<ObjectNode>set("workout", workout).put("updatedAt", T10.toString());
    }

    private Map<String, Object> reschedule(UUID id, long clientVersion, LocalDate date) {
        var payload = json.createObjectNode().putNull("programId").put("scheduledDate", date.toString())
                .put("updatedAt", T10.toString());
        return item("workout_reschedule", id, null, revision(athleteId, id, "workout_reschedule"), clientVersion,
                payload);
    }

    private boolean rescheduleExists(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM sizylift.workout_reschedule WHERE user_id = ? "
                + "AND planned_workout_id = ?", Long.class, athleteId, id) == 1;
    }

    /** Текущая ревизия агрегата этого типа; {@code 0}, если его нет. */
    private long revision(UUID ownerId, UUID aggregateId, String type) {
        return jdbc.query("SELECT revision FROM sizylift.published_aggregate WHERE user_id = ? "
                        + "AND aggregate_id = ? AND type = ?", (row, n) -> row.getLong(1), ownerId, aggregateId, type)
                .stream().findFirst().orElse(0L);
    }

    /** Текущая ревизия агрегата владельца; {@code 0}, если его нет. */
    private long revision(UUID ownerId, UUID aggregateId) {
        return jdbc.query("SELECT revision FROM sizylift.published_aggregate WHERE user_id = ? "
                        + "AND aggregate_id = ?", (row, n) -> row.getLong(1), ownerId, aggregateId)
                .stream().findFirst().orElse(0L);
    }

    private Map<String, Object> item(String type, UUID id, UUID ownerId, long baseRevision, long clientVersion,
                                     JsonNode payload) {
        var item = new HashMap<String, Object>();
        item.put("type", type);
        item.put("id", id);
        item.put("ownerId", ownerId);
        item.put("baseRevision", baseRevision);
        item.put("changedAt", T10.plusSeconds(clientVersion).toString());
        item.put("clientId", "device-" + UUID.randomUUID());
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

    private String legacy(String subject, String type, UUID id, long version, JsonNode payload, boolean deleted)
            throws Exception {
        return legacyResult(subject, type, id, version, payload, deleted).get("status").asString();
    }

    private JsonNode legacyResult(String subject, String type, UUID id, long version, JsonNode payload,
                                  boolean deleted) throws Exception {
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

    private List<JsonNode> changes(String subject, long after) throws Exception {
        var page = body(call(subject, get("/api/v2/sync/changes").param("after", String.valueOf(after))
                .param("limit", "500")).andExpect(status().isOk()));
        var result = new ArrayList<JsonNode>();
        page.get("changes").forEach(result::add);
        return result;
    }

    private long cursor(String subject) throws Exception {
        return body(call(subject, get("/api/v2/sync/changes").param("after", "0").param("limit", "500"))
                .andExpect(status().isOk())).get("nextCursor").asLong();
    }

    private static List<String> kinds(List<JsonNode> changes) {
        return changes.stream().map(c -> c.get("type").asString() + ":" + c.get("kind").asString()).toList();
    }

    /** Последняя запись ленты по каждому агрегату. */
    private static Map<UUID, JsonNode> byId(List<JsonNode> changes) {
        var result = new HashMap<UUID, JsonNode>();
        changes.forEach(change -> result.put(UUID.fromString(change.get("id").asString()), change));
        return result;
    }

    private UUID link(String coachSubject, String athleteSubject) throws Exception {
        call(coachSubject, put("/api/v2/coaching/coach-mode").contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}")).andExpect(status().isOk());
        var token = body(call(coachSubject, post("/api/v2/coaching/invites")).andExpect(status().isCreated()))
                .get("token").asString();
        var link = body(call(athleteSubject, post("/api/v2/coaching/invites/" + token + "/accept"))
                .andExpect(status().isOk()));
        return UUID.fromString(link.get("id").asString());
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
