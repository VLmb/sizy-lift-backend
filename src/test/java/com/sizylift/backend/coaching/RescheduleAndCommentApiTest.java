package com.sizylift.backend.coaching;

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

/** Переносы тренировок и комментарии (docs/coaching.md): права, каскады, ленты, разрыв. */
@SpringBootTest
@AutoConfigureMockMvc
class RescheduleAndCommentApiTest extends PostgresIntegrationTest {

    static final String ISSUER = "http://keycloak.test/realms/sizylift";
    static final Instant T10 = Instant.parse("2026-10-20T10:00:00Z");
    /** Плановая тренировка из общей фикстуры программы. */
    static final UUID PLANNED_WORKOUT = UUID.fromString("30000000-0000-4000-8000-000000000004");
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
    UUID linkId;
    UUID athleteId;

    @BeforeEach
    void linkedPair() throws Exception {
        coach = UUID.randomUUID().toString();
        athlete = UUID.randomUUID().toString();
        stranger = UUID.randomUUID().toString();
        linkId = link(coach, athlete);
        athleteId = userId(athlete);
    }

    @Test
    void athleteReschedulesTheCoachWorkoutWithoutTouchingTheProgram() throws Exception {
        var program = coachProgram(PLANNED_DATE);
        var coachCursor = cursor(coach);

        var result = push(athlete, reschedule(1, MOVED_TO, program));

        assertThat(result.get("status").asString()).isEqualTo("accepted");
        assertThat(jdbc.queryForObject("SELECT scheduled_date FROM sizylift.workout_reschedule WHERE user_id = ? "
                + "AND planned_workout_id = ?", LocalDate.class, athleteId, PLANNED_WORKOUT)).isEqualTo(MOVED_TO);
        assertThat(jdbc.queryForObject("SELECT workout_date FROM sizylift.planned_workout WHERE user_id = ? "
                + "AND id = ?", LocalDate.class, athleteId, PLANNED_WORKOUT)).isEqualTo(PLANNED_DATE);
        var seen = changes(coach, coachCursor);
        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).get("type").asString()).isEqualTo("workout_reschedule");
        assertThat(seen.get(0).get("payload").get("scheduledDate").asString()).isEqualTo(MOVED_TO.toString());

        var byCoach = item("workout_reschedule", PLANNED_WORKOUT, athleteId, result.get("revision").asLong(), 1,
                reschedulePayload(LocalDate.parse("2026-10-16"), program));
        assertThat(push(coach, byCoach).get("error").asString()).isEqualTo("forbidden");
    }

    @Test
    void rescheduleSurvivesProgramEditsThatKeepTheDate() throws Exception {
        var program = coachProgram(PLANNED_DATE);
        push(athlete, reschedule(1, MOVED_TO, program));

        var edited = programPayload(PLANNED_DATE);
        edited.put("name", "Новое имя");
        push(coach, item("program", program, athleteId, revision(athleteId, program), 2, edited));

        assertThat(rescheduleExists()).isTrue();
    }

    @Test
    void coachChangingTheDateRemovesTheRescheduleAndTellsBothSides() throws Exception {
        var program = coachProgram(PLANNED_DATE);
        push(athlete, reschedule(1, MOVED_TO, program));
        var athleteCursor = cursor(athlete);
        var coachCursor = cursor(coach);

        var result = push(coach, item("program", program, athleteId, revision(athleteId, program), 2,
                programPayload(LocalDate.parse("2026-10-17"))));

        assertThat(result.get("status").asString()).isEqualTo("accepted");
        assertThat(rescheduleExists()).isFalse();
        for (var feed : List.of(changes(athlete, athleteCursor), changes(coach, coachCursor))) {
            assertThat(kinds(feed)).containsExactly("workout_reschedule:delete", "program:upsert");
        }

        assertThat(push(athlete, reschedule(2, MOVED_TO, program)).get("status").asString())
                .as("новый перенос после смены даты снова действует").isEqualTo("accepted");
        assertThat(rescheduleExists()).isTrue();
    }

    @Test
    void reschedulingBackToTheProgramDateRemovesTheReschedule() throws Exception {
        var program = coachProgram(PLANNED_DATE);
        push(athlete, reschedule(1, MOVED_TO, program));
        var athleteCursor = cursor(athlete);
        var coachCursor = cursor(coach);

        var result = push(athlete, reschedule(2, PLANNED_DATE, program));

        assertThat(result.get("status").asString()).isEqualTo("accepted");
        assertThat(result.get("payloadRewritten").asBoolean()).as("клиент берёт delete из ленты").isTrue();
        assertThat(rescheduleExists()).isFalse();
        assertThat(jdbc.queryForObject("SELECT deleted FROM sizylift.published_aggregate WHERE user_id = ? "
                + "AND type = 'workout_reschedule' AND aggregate_id = ?", Boolean.class, athleteId, PLANNED_WORKOUT))
                .isTrue();
        for (var feed : List.of(changes(athlete, athleteCursor), changes(coach, coachCursor))) {
            assertThat(kinds(feed)).containsExactly("workout_reschedule:delete");
            assertThat(feed.get(0).get("revision").asLong()).isEqualTo(result.get("revision").asLong());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sizylift.coach_event WHERE link_id = ? "
                + "AND kind = 'workout_rescheduled'", Long.class, linkId)).isZero();

        assertThat(push(athlete, reschedule(3, MOVED_TO, program)).get("payloadRewritten").asBoolean())
                .as("новый перенос — обычная запись").isFalse();
        assertThat(rescheduleExists()).isTrue();
    }

    @Test
    void removingThePlannedWorkoutOrTheProgramRemovesTheReschedule() throws Exception {
        var program = coachProgram(PLANNED_DATE);
        push(athlete, reschedule(1, MOVED_TO, program));
        var withoutWorkouts = programPayload(PLANNED_DATE);
        ((ObjectNode) withoutWorkouts.get("cycles").get(0).get("weeks").get(0)).putArray("workouts");

        push(coach, item("program", program, athleteId, revision(athleteId, program), 2, withoutWorkouts));
        assertThat(rescheduleExists()).isFalse();

        var restored = push(coach, item("program", program, athleteId, revision(athleteId, program), 3,
                programPayload(PLANNED_DATE)));
        push(athlete, reschedule(2, MOVED_TO, program));
        assertThat(rescheduleExists()).isTrue();
        var deletion = item("program", program, athleteId, restored.get("revision").asLong(), 4, null);
        deletion.put("deleted", true);
        assertThat(push(coach, deletion).get("status").asString()).isEqualTo("accepted");
        assertThat(rescheduleExists()).isFalse();
    }

    @Test
    void bothSidesCommentAndEachEditsOnlyTheirOwn() throws Exception {
        var workout = publishWorkout();
        var mine = UUID.randomUUID();
        var coachComment = UUID.randomUUID();

        var athleteResult = push(athlete, item("workout_comment", mine, null, 0, 1, comment(workout, "Тяжело")));
        var coachResult = push(coach, item("workout_comment", coachComment, athleteId, 0, 1,
                comment(workout, "Отлично")));

        assertThat(athleteResult.get("status").asString()).isEqualTo("accepted");
        assertThat(coachResult.get("status").asString()).isEqualTo("accepted");
        var athleteFeed = byId(changes(athlete, 0));
        assertThat(athleteFeed.get(coachComment).get("authorId").asString()).isEqualTo(userId(coach).toString());
        assertThat(athleteFeed.get(coachComment).get("payload").get("text").asString()).isEqualTo("Отлично");
        assertThat(athleteFeed.get(mine).get("authorId").isNull()).isTrue();
        assertThat(byId(changes(coach, 0))).containsKeys(mine, coachComment);

        assertThat(push(athlete, item("workout_comment", coachComment, null, coachResult.get("revision").asLong(),
                2, comment(workout, "Правлю тренера"))).get("error").asString()).isEqualTo("forbidden");
        assertThat(push(coach, item("workout_comment", mine, athleteId, athleteResult.get("revision").asLong(), 2,
                comment(workout, "Правлю подопечного"))).get("error").asString()).isEqualTo("forbidden");
        assertThat(push(coach, item("workout_comment", coachComment, athleteId,
                coachResult.get("revision").asLong(), 2, comment(workout, "Отлично!"))).get("status").asString())
                .isEqualTo("accepted");
        assertThat(push(stranger, item("workout_comment", UUID.randomUUID(), athleteId, 0, 1,
                comment(workout, "Чужой"))).get("error").asString()).isEqualTo("forbidden");
    }

    @Test
    void commentsSurviveWorkoutEditsAndGoWithTheWorkout() throws Exception {
        var workout = publishWorkout();
        var coachComment = UUID.randomUUID();
        push(coach, item("workout_comment", coachComment, athleteId, 0, 1, comment(workout, "Отлично")));
        push(athlete, item("workout_comment", UUID.randomUUID(), null, 0, 1, comment(workout, "Спасибо")));

        var edited = fixture("workout");
        edited.put("title", "Переименована");
        assertThat(legacy(athlete, "workout", workout, 2, edited, false)).isEqualTo("accepted");
        assertThat(commentCount()).isEqualTo(2);

        var coachCursor = cursor(coach);
        assertThat(legacy(athlete, "workout", workout, 3, null, true)).isEqualTo("accepted");

        assertThat(commentCount()).isZero();
        var coachFeed = changes(coach, coachCursor);
        assertThat(kinds(coachFeed)).containsExactly("workout_comment:delete", "workout_comment:delete",
                "workout:delete");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sizylift.published_aggregate WHERE user_id = ? "
                + "AND type = 'workout_comment' AND deleted", Long.class, athleteId)).isEqualTo(2);
    }

    @Test
    void afterTheBreakTheAthleteKeepsCoachCommentsAndTheCoachLosesThem() throws Exception {
        var workout = publishWorkout();
        var coachComment = UUID.randomUUID();
        var revision = push(coach, item("workout_comment", coachComment, athleteId, 0, 1,
                comment(workout, "Отлично"))).get("revision").asLong();
        var coachCursor = cursor(coach);

        call(athlete, delete("/api/v2/coaching/links/" + linkId)).andExpect(status().isNoContent());

        assertThat(byId(changes(athlete, 0)).get(coachComment).get("kind").asString()).isEqualTo("upsert");
        assertThat(kinds(changes(coach, coachCursor))).contains("workout_comment:revoke")
                .allMatch(kind -> kind.endsWith(":revoke"));
        assertThat(push(coach, item("workout_comment", coachComment, athleteId, revision, 2,
                comment(workout, "Поздно"))).get("error").asString()).isEqualTo("forbidden");
        assertThat(push(athlete, item("workout_comment", coachComment, null, revision, 1,
                comment(workout, "Не моё"))).get("error").asString()).isEqualTo("forbidden");
    }

    @Test
    void v1DoesNotAcceptTheNewTypes() throws Exception {
        assertThat(legacyResult(athlete, "workout_comment", UUID.randomUUID(), 1,
                comment(UUID.randomUUID(), "x"), false).get("error").asString()).isEqualTo("unsupported_type");
        assertThat(legacyResult(athlete, "workout_reschedule", UUID.randomUUID(), 1,
                reschedulePayload(MOVED_TO, UUID.randomUUID()), false).get("error").asString())
                .isEqualTo("unsupported_type");
    }

    private UUID coachProgram(LocalDate date) throws Exception {
        var program = UUID.randomUUID();
        assertThat(push(coach, item("program", program, athleteId, 0, 1, programPayload(date)))
                .get("status").asString()).isEqualTo("accepted");
        return program;
    }

    private UUID publishWorkout() throws Exception {
        var workout = UUID.randomUUID();
        assertThat(legacy(athlete, "workout", workout, 1, fixture("workout"), false)).isEqualTo("accepted");
        return workout;
    }

    private ObjectNode programPayload(LocalDate date) throws Exception {
        var program = fixture("program");
        ((ObjectNode) program.get("cycles").get(0).get("weeks").get(0).get("workouts").get(0))
                .put("workoutDate", date.toString());
        return program;
    }

    private Map<String, Object> reschedule(long clientVersion, LocalDate date, UUID program) {
        return item("workout_reschedule", PLANNED_WORKOUT, null, revision(athleteId, PLANNED_WORKOUT),
                clientVersion, reschedulePayload(date, program));
    }

    private ObjectNode reschedulePayload(LocalDate date, UUID program) {
        return json.createObjectNode().put("programId", program.toString()).put("scheduledDate", date.toString())
                .put("updatedAt", T10.toString());
    }

    private ObjectNode comment(UUID workout, String text) {
        return json.createObjectNode().put("workoutId", workout.toString()).put("text", text)
                .put("createdAt", T10.toString()).put("updatedAt", T10.toString());
    }

    private boolean rescheduleExists() {
        return jdbc.queryForObject("SELECT count(*) FROM sizylift.workout_reschedule WHERE user_id = ? "
                + "AND planned_workout_id = ?", Long.class, athleteId, PLANNED_WORKOUT) == 1;
    }

    private long commentCount() {
        return jdbc.queryForObject("SELECT count(*) FROM sizylift.workout_comment WHERE user_id = ?", Long.class,
                athleteId);
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
