package com.sizylift.backend.notifications;

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
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Какие изменения ставят уведомления второй стороне (ADR-0009, «Контекст») и что в них лежит. */
@SpringBootTest
@AutoConfigureMockMvc
class NotificationTriggersApiTest extends PostgresIntegrationTest {

    static final String ISSUER = "http://keycloak.test/realms/sizylift";
    static final Instant T10 = Instant.parse("2026-10-20T10:00:00Z");
    static final UUID PLANNED_WORKOUT = UUID.fromString("30000000-0000-4000-8000-000000000004");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    String coach;
    String athlete;
    UUID coachId;
    UUID athleteId;

    @BeforeEach
    void users() throws Exception {
        coach = UUID.randomUUID().toString();
        athlete = UUID.randomUUID().toString();
        coachId = userId(coach);
        athleteId = userId(athlete);
    }

    @Test
    void acceptedInviteNotifiesTheCoach() throws Exception {
        link();

        var notification = only(coachId);
        assertThat(notification.get("kind")).isEqualTo("invite_accepted");
        assertThat(data(notification)).containsOnlyKeys("linkId", "actorId")
                .containsEntry("actorId", athleteId.toString());
    }

    @Test
    void coachProgramNotifiesTheAthleteOnceForRepeatedEdits() throws Exception {
        link();
        var program = UUID.randomUUID();
        var revision = push(coach, item("program", program, athleteId, 0, 1, fixture("program")))
                .get("revision").asLong();
        for (int version = 2; version <= 3; version++) {
            revision = push(coach, item("program", program, athleteId, revision, version, fixture("program")))
                    .get("revision").asLong();
        }

        var notification = only(athleteId);
        assertThat(notification.get("kind")).isEqualTo("program_updated");
        assertThat(data(notification)).containsOnlyKeys("ownerId", "aggregateId", "actorId")
                .containsEntry("aggregateId", program.toString())
                .containsEntry("actorId", coachId.toString());
        assertThat(notification.get("data").toString()).as("в уведомлении только id")
                .doesNotContain(fixture("program").get("name").asString());
    }

    @Test
    void newWorkoutNotifiesTheCoachButEditsAndHealthDataDoNot() throws Exception {
        link();
        clear(coachId);
        var workout = UUID.randomUUID();

        legacy(athlete, "workout", workout, 1, fixture("workout"));
        var edited = fixture("workout");
        edited.put("title", "Правка");
        legacy(athlete, "workout", workout, 2, edited);
        legacy(athlete, "injury", UUID.randomUUID(), 1, fixture("injury"));
        legacy(athlete, "program", UUID.randomUUID(), 1, fixture("program"));

        assertThat(only(coachId).get("kind")).isEqualTo("workout_completed");
    }

    @Test
    void rescheduleAndCommentsNotifyTheOtherSide() throws Exception {
        link();
        var program = UUID.randomUUID();
        push(coach, item("program", program, athleteId, 0, 1, fixture("program")));
        var workout = UUID.randomUUID();
        legacy(athlete, "workout", workout, 1, fixture("workout"));
        clear(coachId);
        clear(athleteId);

        push(athlete, item("workout_reschedule", PLANNED_WORKOUT, null, 0, 1, json.createObjectNode()
                .put("programId", program.toString()).put("scheduledDate", "2026-10-15")
                .put("updatedAt", T10.toString())));
        var athleteComment = push(athlete, item("workout_comment", UUID.randomUUID(), null, 0, 1,
                comment(workout, "Тяжело")));
        push(coach, item("workout_comment", UUID.randomUUID(), athleteId, 0, 1, comment(workout, "Отлично")));
        push(athlete, item("workout_comment", UUID.fromString(athleteComment.get("id").asString()), null,
                athleteComment.get("revision").asLong(), 2, comment(workout, "Правка без уведомления")));

        assertThat(kinds(coachId)).containsExactlyInAnyOrder("workout_rescheduled", "comment_added");
        assertThat(kinds(athleteId)).containsExactly("comment_added");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sizylift.notification_outbox "
                + "WHERE data::text LIKE '%Тяжело%' OR data::text LIKE '%Отлично%'", Long.class))
                .as("текст комментария не попадает в уведомление").isZero();
    }

    @Test
    void nothingIsQueuedWithoutALinkOrForRejectedWrites() throws Exception {
        legacy(athlete, "workout", UUID.randomUUID(), 1, fixture("workout"));
        var forbidden = push(coach, item("program", UUID.randomUUID(), athleteId, 0, 1, fixture("program")));

        assertThat(forbidden.get("error").asString()).isEqualTo("forbidden");
        assertThat(kinds(coachId)).isEmpty();
        assertThat(kinds(athleteId)).isEmpty();
    }

    private void link() throws Exception {
        call(coach, put("/api/v2/coaching/coach-mode").contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}")).andExpect(status().isOk());
        var token = body(call(coach, post("/api/v2/coaching/invites")).andExpect(status().isCreated()))
                .get("token").asString();
        call(athlete, post("/api/v2/coaching/invites/" + token + "/accept")).andExpect(status().isOk());
    }

    private Map<String, Object> only(UUID user) {
        var rows = jdbc.queryForList("SELECT kind, data::text AS data FROM sizylift.notification_outbox "
                + "WHERE user_id = ?", user);
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    private List<String> kinds(UUID user) {
        return jdbc.queryForList("SELECT kind FROM sizylift.notification_outbox WHERE user_id = ?", String.class,
                user);
    }

    private Map<String, String> data(Map<String, Object> row) throws Exception {
        var result = new HashMap<String, String>();
        json.readTree((String) row.get("data")).properties()
                .forEach(entry -> result.put(entry.getKey(), entry.getValue().asString()));
        return result;
    }

    private void clear(UUID user) {
        jdbc.update("DELETE FROM sizylift.notification_outbox WHERE user_id = ?", user);
    }

    private ObjectNode comment(UUID workout, String text) {
        return json.createObjectNode().put("workoutId", workout.toString()).put("text", text)
                .put("createdAt", T10.toString()).put("updatedAt", T10.toString());
    }

    private Map<String, Object> item(String type, UUID id, UUID ownerId, long baseRevision, long clientVersion,
                                     JsonNode payload) {
        var item = new HashMap<String, Object>();
        item.put("type", type);
        item.put("id", id);
        item.put("ownerId", ownerId);
        item.put("baseRevision", baseRevision);
        item.put("changedAt", T10.plusSeconds(clientVersion).toString());
        item.put("clientId", "device-1");
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

    private void legacy(String subject, String type, UUID id, long version, JsonNode payload) throws Exception {
        var item = new HashMap<String, Object>();
        item.put("type", type);
        item.put("id", id);
        item.put("version", version);
        item.put("schemaVersion", 1);
        item.put("deleted", false);
        item.put("payload", payload);
        var result = body(call(subject, post("/api/v1/publication/batch").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("items", List.of(item))))).andExpect(status().isOk()))
                .get("results").get(0);
        assertThat(result.get("status").asString()).isEqualTo("accepted");
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
