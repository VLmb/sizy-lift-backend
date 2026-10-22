package com.sizylift.backend.coaching;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
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

/** Связь тренера и подопечного (docs/coaching.md): приглашение, ленты, запись тренера, травмы, разрыв. */
@SpringBootTest
@AutoConfigureMockMvc
class CoachingApiTest extends PostgresIntegrationTest {

    static final String ISSUER = "http://keycloak.test/realms/sizylift";
    static final Instant T10 = Instant.parse("2026-10-20T10:00:00Z");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    String coach;
    String athlete;
    String stranger;

    @BeforeEach
    void newUsers() {
        coach = UUID.randomUUID().toString();
        athlete = UUID.randomUUID().toString();
        stranger = UUID.randomUUID().toString();
    }

    @Test
    void invitesNeedCoachModeAndOnlyTheTokenHashIsStored() throws Exception {
        call(coach, post("/api/v2/coaching/invites")).andExpect(status().isForbidden());

        var token = invite(coach);

        assertThat(token).matches("[A-Za-z0-9_-]{22}");
        var stored = jdbc.queryForObject("SELECT token_hash FROM sizylift.coaching_invite WHERE coach_user_id = ?",
                byte[].class, userId(coach));
        assertThat(stored).isEqualTo(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.US_ASCII)));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = 'sizylift' AND table_name = 'coaching_invite' AND column_name = 'token'
                """, Long.class)).isZero();
    }

    @Test
    void athleteSeesWhoInvitesAndWhatTheCoachWillSee() throws Exception {
        var token = invite(coach);
        var profile = fixture("athlete_profile");
        profile.put("displayName", "Тренер Иван");
        publish(coach, "athlete_profile", UUID.randomUUID(), 1, profile);

        var preview = body(call(athlete, get("/api/v2/coaching/invites/" + token)).andExpect(status().isOk()));

        assertThat(preview.get("coachName").asString()).isEqualTo("Тренер Иван");
        assertThat(texts(preview.get("coachSees"))).containsExactlyInAnyOrder("workout", "program", "exercise",
                "athlete_profile", "one_rep_max");
        assertThat(texts(preview.get("coachSeesWithHealthAccess"))).contains("injury", "contraindication");
    }

    @Test
    void invitesAreSingleUseExpireAndCannotBeAcceptedByTheCoach() throws Exception {
        var token = invite(coach);
        call(coach, post("/api/v2/coaching/invites/" + token + "/accept")).andExpect(status().isConflict());
        accept(athlete, token);
        call(stranger, post("/api/v2/coaching/invites/" + token + "/accept")).andExpect(status().isGone());

        var expired = invite(coach);
        jdbc.update("UPDATE sizylift.coaching_invite SET expires_at = now() - interval '1 second' "
                + "WHERE accepted_at IS NULL AND coach_user_id = ?", userId(coach));
        call(stranger, post("/api/v2/coaching/invites/" + expired + "/accept")).andExpect(status().isGone());
        call(stranger, get("/api/v2/coaching/invites/" + expired)).andExpect(status().isGone());
        call(stranger, post("/api/v2/coaching/invites/not-a-token/accept")).andExpect(status().isNotFound());
        call(stranger, post("/api/v2/coaching/invites/AAAAAAAAAAAAAAAAAAAAAA/accept"))
                .andExpect(status().isNotFound());
    }

    @Test
    void athleteHasOnlyOneActiveCoach() throws Exception {
        accept(athlete, invite(coach));

        call(athlete, post("/api/v2/coaching/invites/" + invite(stranger) + "/accept"))
                .andExpect(status().isConflict());

        var overview = body(call(athlete, get("/api/v2/coaching")).andExpect(status().isOk()));
        assertThat(overview.get("coach").get("coachId").asString()).isEqualTo(userId(coach).toString());
        var coachView = body(call(coach, get("/api/v2/coaching")).andExpect(status().isOk()));
        assertThat(coachView.get("coachEnabled").asBoolean()).isTrue();
        assertThat(coachView.get("athletes").size()).isEqualTo(1);
    }

    @Test
    void newLinkSeedsTheCoachFeedWithoutHealthData() throws Exception {
        var workout = UUID.randomUUID();
        publish(athlete, "workout", workout, 1, fixture("workout"));
        publish(athlete, "injury", UUID.randomUUID(), 1, fixture("injury"));

        accept(athlete, invite(coach));

        var changes = changes(coach, 0);
        assertThat(changes).hasSize(1);
        assertThat(changes.get(0).get("id").asString()).isEqualTo(workout.toString());
        assertThat(changes.get(0).get("ownerId").asString()).isEqualTo(userId(athlete).toString());
        assertThat(changes.get(0).get("payload").get("quality").asString()).isEqualTo("great");
    }

    @Test
    void athleteWritesReachTheCoachAccordingToHealthAccess() throws Exception {
        var linkId = accept(athlete, invite(coach));
        var cursor = cursor(coach);
        var injury = UUID.randomUUID();

        publish(athlete, "workout", UUID.randomUUID(), 1, fixture("workout"));
        publish(athlete, "injury", injury, 1, fixture("injury"));

        assertThat(types(changes(coach, cursor))).containsExactly("workout");

        cursor = cursor(coach);
        healthAccess(athlete, linkId, true).andExpect(status().isOk());
        var granted = changes(coach, cursor);
        assertThat(types(granted)).containsExactly("injury");
        assertThat(granted.get(0).get("payload").get("name").asString()).isNotBlank();

        cursor = cursor(coach);
        healthAccess(athlete, linkId, false).andExpect(status().isOk());
        var revoked = changes(coach, cursor);
        assertThat(revoked).hasSize(1);
        assertThat(revoked.get(0).get("id").asString()).isEqualTo(injury.toString());
        assertThat(revoked.get(0).get("kind").asString()).isEqualTo("revoke");
        assertThat(revoked.get(0).get("payload").isNull()).isTrue();
    }

    @Test
    void onlyTheAthleteChangesHealthAccess() throws Exception {
        var linkId = accept(athlete, invite(coach));

        healthAccess(coach, linkId, true).andExpect(status().isForbidden());
        healthAccess(stranger, linkId, true).andExpect(status().isNotFound());
    }

    @Test
    void coachWritesTheAthletesProgramAndIsItsOnlyWriter() throws Exception {
        accept(athlete, invite(coach));
        var program = UUID.randomUUID();
        var athleteId = userId(athlete);

        var created = push(coach, programItem(program, athleteId, 0, 1, "Программа тренера")).get(0);

        assertThat(created.get("status").asString()).isEqualTo("accepted");
        assertThat(jdbc.queryForObject("SELECT author_user_id FROM sizylift.published_aggregate "
                + "WHERE user_id = ? AND aggregate_id = ?", UUID.class, athleteId, program)).isEqualTo(userId(coach));
        assertThat(jdbc.queryForObject("SELECT name FROM sizylift.program WHERE user_id = ? AND id = ?",
                String.class, athleteId, program)).isEqualTo("Программа тренера");
        var athleteFeed = changes(athlete, 0);
        assertThat(athleteFeed.get(0).get("payload").get("name").asString()).isEqualTo("Программа тренера");
        assertThat(changes(coach, 0).get(0).get("id").asString()).isEqualTo(program.toString());

        var revision = created.get("revision").asLong();
        assertThat(outcome(push(athlete, programItem(program, null, revision, 1, "Моя правка")))).isEqualTo("rejected");
        assertThat(push(athlete, programItem(program, null, revision, 2, "x")).get(0).get("error").asString())
                .isEqualTo("forbidden");
        assertThat(legacyResult(athlete, "program", program, 99, fixture("program")).get("error").asString())
                .isEqualTo("forbidden");
        assertThat(outcome(push(coach, programItem(program, athleteId, revision, 2, "Правка тренера"))))
                .isEqualTo("accepted");
        assertThat(jdbc.queryForObject("SELECT author_user_id FROM sizylift.published_aggregate "
                + "WHERE user_id = ? AND aggregate_id = ?", UUID.class, athleteId, program))
                .as("правка не сбрасывает автора").isEqualTo(userId(coach));
    }

    @Test
    void coachCannotWriteOtherDataAndStrangersCannotWriteAtAll() throws Exception {
        accept(athlete, invite(coach));
        var athleteId = userId(athlete);
        var ownProgram = UUID.randomUUID();
        var base = push(athlete, programItem(ownProgram, null, 0, 1, "Своя")).get(0).get("revision").asLong();

        var workout = item("workout", UUID.randomUUID(), athleteId, 0, 1, fixture("workout"));

        assertThat(push(coach, workout).get(0).get("error").asString()).isEqualTo("forbidden");
        assertThat(push(coach, programItem(ownProgram, athleteId, base, 1, "Чужая")).get(0).get("error")
                .asString()).isEqualTo("forbidden");
        assertThat(push(stranger, programItem(UUID.randomUUID(), athleteId, 0, 1, "x")).get(0).get("error")
                .asString()).isEqualTo("forbidden");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sizylift.published_aggregate WHERE user_id = ?",
                Long.class, athleteId)).isEqualTo(1);
    }

    @Test
    void endingTheLinkRevokesEverythingAndHandsCoachProgramsToTheAthlete() throws Exception {
        var linkId = accept(athlete, invite(coach));
        var athleteId = userId(athlete);
        healthAccess(athlete, linkId, true).andExpect(status().isOk());
        publish(athlete, "workout", UUID.randomUUID(), 1, fixture("workout"));
        publish(athlete, "injury", UUID.randomUUID(), 1, fixture("injury"));
        var program = UUID.randomUUID();
        var coachRevision = push(coach, programItem(program, athleteId, 0, 1, "От тренера")).get(0)
                .get("revision").asLong();
        var coachCursor = cursor(coach);
        var athleteCursor = cursor(athlete);

        call(athlete, delete("/api/v2/coaching/links/" + linkId)).andExpect(status().isNoContent());

        var revoked = changes(coach, coachCursor);
        assertThat(revoked).hasSize(3);
        assertThat(revoked).allMatch(change -> change.get("kind").asString().equals("revoke")
                && change.get("payload").isNull());
        assertThat(changes(coach, 0)).allMatch(change -> change.get("kind").asString().equals("revoke"));
        assertThat(jdbc.queryForObject("SELECT author_user_id FROM sizylift.published_aggregate "
                + "WHERE user_id = ? AND aggregate_id = ?", UUID.class, athleteId, program)).isNull();
        var handedOver = changes(athlete, athleteCursor);
        assertThat(handedOver).hasSize(1);
        var newRevision = handedOver.get(0).get("revision").asLong();
        assertThat(newRevision).isGreaterThan(coachRevision);
        assertThat(handedOver.get(0).get("payload").get("name").asString()).isEqualTo("От тренера");

        assertThat(outcome(push(athlete, programItem(program, null, newRevision, 1, "Теперь моя"))))
                .isEqualTo("accepted");
        assertThat(push(coach, programItem(program, athleteId, newRevision, 2, "Поздно")).get(0).get("error")
                .asString()).isEqualTo("forbidden");
        var afterEnd = cursor(coach);
        publish(athlete, "contraindication", UUID.randomUUID(), 1, fixture("contraindication"));
        assertThat(changes(coach, afterEnd)).isEmpty();
        healthAccess(athlete, linkId, true).andExpect(status().isConflict());
        call(coach, delete("/api/v2/coaching/links/" + linkId)).andExpect(status().isNoContent());
        call(stranger, delete("/api/v2/coaching/links/" + linkId)).andExpect(status().isNotFound());
    }

    @Test
    void coachCanEndTheLinkAndBeInvitedAgain() throws Exception {
        var workout = UUID.randomUUID();
        publish(athlete, "workout", workout, 1, fixture("workout"));
        var linkId = accept(athlete, invite(coach));

        call(coach, delete("/api/v2/coaching/links/" + linkId)).andExpect(status().isNoContent());
        accept(athlete, invite(coach));

        var changes = changes(coach, 0);
        assertThat(changes).hasSize(1);
        assertThat(changes.get(0).get("kind").asString()).isEqualTo("upsert");
        assertThat(changes.get(0).get("id").asString()).isEqualTo(workout.toString());
    }

    @Test
    void photosNeverReachTheCoach() throws Exception {
        accept(athlete, invite(coach));
        publish(athlete, "workout", UUID.randomUUID(), 1, fixture("workout"));
        publish(athlete, "athlete_profile", UUID.randomUUID(), 1, fixture("athlete_profile"));

        assertThat(legacyResult(athlete, "workout_media", UUID.randomUUID(), 1, json.createObjectNode())
                .get("error").asString()).isEqualTo("unsupported_type");
        for (var change : changes(coach, 0)) {
            assertThat(change.get("payload").toString().toLowerCase())
                    .doesNotContain("media", "photo", "avatar");
        }
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

    private ResultActions healthAccess(String subject, UUID linkId, boolean enabled) throws Exception {
        return call(subject, put("/api/v2/coaching/links/" + linkId + "/health-access")
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":" + enabled + "}"));
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

    private static List<String> types(List<JsonNode> changes) {
        return changes.stream().map(change -> change.get("type").asString()).toList();
    }

    private static List<String> texts(JsonNode array) {
        var result = new ArrayList<String>();
        array.forEach(value -> result.add(value.asString()));
        return result;
    }

    private Map<String, Object> programItem(UUID id, UUID ownerId, long baseRevision, long clientVersion,
                                            String name) throws Exception {
        var payload = fixture("program");
        payload.put("name", name);
        return item("program", id, ownerId, baseRevision, clientVersion, payload);
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
                .get("results");
    }

    private static String outcome(JsonNode results) {
        return results.get(0).get("status").asString();
    }

    private void publish(String subject, String type, UUID id, long version, JsonNode payload) throws Exception {
        assertThat(legacyResult(subject, type, id, version, payload).get("status").asString()).isEqualTo("accepted");
    }

    private JsonNode legacyResult(String subject, String type, UUID id, long version, JsonNode payload)
            throws Exception {
        var item = new HashMap<String, Object>();
        item.put("type", type);
        item.put("id", id);
        item.put("version", version);
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

    private ResultActions call(String subject,
                               org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
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
