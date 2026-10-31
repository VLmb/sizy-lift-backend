package com.sizylift.backend.web;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.sizylift.backend.PostgresIntegrationTest;
import com.sizylift.backend.publication.dto.payload.ProgramPayloadV1;
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

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.sizylift.backend.identity.deletion.KeycloakAccountDeletion accountDeletion;

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


    @Test
    void diaryReturnsRescheduledDaysActualSnapshotsAndCommentsWithOwnerChecks() throws Exception {
        var programId = UUID.randomUUID();
        var workoutId = UUID.randomUUID();
        publish(owner, "program", programId, 1, fixture("program"));
        publish(owner, "workout", workoutId, 1, fixture("workout"));
        var athleteId = userId(owner);
        var link = accept(owner, invite(coach));
        var plannedId = UUID.fromString("30000000-0000-4000-8000-000000000004");
        var reschedule = json.valueToTree(Map.of("programId", programId, "scheduledDate", "2026-10-16",
                "updatedAt", T10.toString()));
        assertThat(push(owner, item("workout_reschedule", plannedId, null, 0, 1, reschedule))
                .get("status").asString()).isEqualTo("accepted");
        var comment = json.valueToTree(Map.of("workoutId", workoutId, "text", "Ответ тренера",
                "createdAt", T10.toString(), "updatedAt", T10.toString()));
        assertThat(push(coach, item("workout_comment", UUID.randomUUID(), athleteId, 0, 1, comment))
                .get("status").asString()).isEqualTo("accepted");
        var timeline = body(call(coach, get("/api/v2/web/timeline").param("ownerId", athleteId.toString())
                .param("from", "2026-10-01").param("to", "2026-10-31")).andExpect(status().isOk()));
        assertThat(timeline.get("planned").get(0).get("scheduledDate").asString()).isEqualTo("2026-10-16");
        assertThat(timeline.get("planned").get(0).get("originalDate").asString()).isEqualTo("2026-10-14");
        assertThat(timeline.get("workouts").get(0).get("id").asString()).isEqualTo(workoutId.toString());
        assertThat(timeline.get("workouts").get(0).get("recordCount").asInt()).isEqualTo(1);
        assertThat(timeline.get("planned").get(0).get("linkedWorkoutDate").asString()).isEqualTo("2026-10-14");
        var outsideFact = body(call(owner, get("/api/v2/web/timeline")
                .param("from", "2026-10-16").param("to", "2026-10-16")).andExpect(status().isOk()));
        assertThat(outsideFact.get("workouts").size()).isZero();
        assertThat(outsideFact.get("planned").get(0).get("linkedWorkoutDate").asString()).isEqualTo("2026-10-14");
        var detail = body(call(coach, get("/api/v2/web/workouts/" + workoutId)
                .param("ownerId", athleteId.toString())).andExpect(status().isOk()));
        assertThat(detail.get("workout").get("planSetSnapshots").size()).isEqualTo(1);
        assertThat(detail.get("comments").get(0).get("payload").get("text").asString()).isEqualTo("Ответ тренера");
        call(stranger, get("/api/v2/web/workouts/" + workoutId).param("ownerId", athleteId.toString()))
                .andExpect(status().isNotFound());
        call(owner, get("/api/v2/web/timeline").param("from", "2026-10-31").param("to", "2026-10-01"))
                .andExpect(status().isBadRequest());
        call(owner, get("/api/v2/web/timeline").param("from", "2020-01-01").param("to", "2026-10-01"))
                .andExpect(status().isBadRequest());
        call(owner, delete("/api/v2/coaching/links/" + link)).andExpect(status().isNoContent());
        call(coach, get("/api/v2/web/timeline").param("ownerId", athleteId.toString())
                .param("from", "2026-10-01").param("to", "2026-10-31")).andExpect(status().isNotFound());
    }

    @Test
    void profileNeverLeaksHealthWithoutPermissionAndRevocationTakesEffectImmediately() throws Exception {
        publish(owner, "athlete_profile", UUID.randomUUID(), 1, fixture("athlete_profile"));
        publish(owner, "injury", UUID.randomUUID(), 1, fixture("injury"));
        publish(owner, "contraindication", UUID.randomUUID(), 1, fixture("contraindication"));
        var link = accept(owner, invite(coach));
        var ownerId = userId(owner).toString();
        var hidden = body(call(coach, get("/api/v2/web/profile").param("ownerId", ownerId))
                .andExpect(status().isOk()));
        assertThat(hidden.get("profile").isNull()).isFalse();
        assertThat(hidden.get("healthAccess").asBoolean()).isFalse();
        assertThat(hidden.get("injuries").size()).isZero();
        assertThat(hidden.get("contraindications").size()).isZero();
        call(coach, put("/api/v2/coaching/links/" + link + "/health-access").contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}")).andExpect(status().isForbidden());
        call(owner, put("/api/v2/coaching/links/" + link + "/health-access").contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}")).andExpect(status().isOk());
        var visible = body(call(coach, get("/api/v2/web/profile").param("ownerId", ownerId))
                .andExpect(status().isOk()));
        assertThat(visible.get("injuries").size()).isEqualTo(1);
        assertThat(visible.get("contraindications").size()).isEqualTo(1);
        call(owner, put("/api/v2/coaching/links/" + link + "/health-access").contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}")).andExpect(status().isOk());
        var revoked = body(call(coach, get("/api/v2/web/profile").param("ownerId", ownerId))
                .andExpect(status().isOk()));
        assertThat(revoked.get("injuries").size()).isZero();
        call(stranger, get("/api/v2/web/profile").param("ownerId", ownerId)).andExpect(status().isNotFound());
        assertThat(push(coach, item("athlete_profile", UUID.randomUUID(), userId(owner), 0, 1,
                fixture("athlete_profile"))).get("error").asString()).isEqualTo("forbidden");
    }

    @Test
    void archiveChecksRevisionAndAuthorAndRestoresProgramThroughSync() throws Exception {
        var id = UUID.randomUUID();
        publish(owner, "program", id, 1, fixture("program"));
        var revision = body(call(owner, get("/api/v2/web/programs/" + id)).andExpect(status().isOk()))
                .get("revision").asLong();
        var link = accept(owner, invite(coach));
        call(coach, post("/api/v2/web/programs/" + id + "/archive").param("ownerId", userId(owner).toString())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("revision", revision))))
                .andExpect(status().isForbidden());
        call(owner, post("/api/v2/web/programs/" + id + "/archive").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("revision", revision + 1)))).andExpect(status().isConflict());
        call(owner, post("/api/v2/web/programs/" + id + "/archive").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("revision", revision)))).andExpect(status().isNoContent());
        call(owner, get("/api/v2/web/programs/" + id)).andExpect(status().isNotFound());
        var archive = body(call(owner, get("/api/v2/web/programs/archive")).andExpect(status().isOk()));
        assertThat(archive.size()).isEqualTo(1);
        call(stranger, get("/api/v2/web/programs/archive").param("ownerId", userId(owner).toString()))
                .andExpect(status().isNotFound());
        call(owner, post("/api/v2/web/programs/" + id + "/restore")).andExpect(status().isNoContent());
        var restored = body(call(owner, get("/api/v2/web/programs/" + id)).andExpect(status().isOk()));
        assertThat(restored.get("revision").asLong()).isGreaterThan(revision);
        assertThat(json.treeToValue(restored.get("program"), ProgramPayloadV1.class).cycles())
                .isEqualTo(json.treeToValue(fixture("program"), ProgramPayloadV1.class).cycles());
        assertThat(body(call(owner, get("/api/v2/web/programs/archive")).andExpect(status().isOk())).size()).isZero();
        call(owner, delete("/api/v2/coaching/links/" + link)).andExpect(status().isNoContent());
    }

    @Test
    void revokedInviteIsGoneAndOnlyItsCreatorCanRevokeIt() throws Exception {
        var token = invite(coach);
        var pending = body(call(coach, get("/api/v2/coaching/invites")).andExpect(status().isOk()));
        assertThat(pending.size()).isEqualTo(1);
        assertThat(pending.get(0).has("token")).isFalse();
        var id = pending.get(0).get("id").asString();
        call(stranger, delete("/api/v2/coaching/invites/" + id)).andExpect(status().isNotFound());
        call(coach, delete("/api/v2/coaching/invites/" + id)).andExpect(status().isNoContent());
        call(owner, post("/api/v2/coaching/invites/" + token + "/accept")).andExpect(status().isGone());
        assertThat(body(call(coach, get("/api/v2/coaching/invites")).andExpect(status().isOk())).size()).isZero();
    }

    @Test
    void replacingCoachWithAnInvalidInviteKeepsTheOriginalLinkAndHealthConsentIsExplicit() throws Exception {
        var original = accept(owner, invite(coach));
        var nextToken = invite(stranger);
        var pending = body(call(stranger, get("/api/v2/coaching/invites")).andExpect(status().isOk()));
        call(stranger, delete("/api/v2/coaching/invites/" + pending.get(0).get("id").asString()))
                .andExpect(status().isNoContent());
        var request = json.writeValueAsString(Map.of("replaceLinkId", original, "healthAccess", true));
        call(owner, post("/api/v2/coaching/invites/" + nextToken + "/accept").contentType(MediaType.APPLICATION_JSON)
                .content(request)).andExpect(status().isGone());
        var overview = body(call(owner, get("/api/v2/coaching")).andExpect(status().isOk()));
        assertThat(overview.get("coach").get("id").asString()).isEqualTo(original.toString());
        var valid = invite(stranger);
        call(owner, post("/api/v2/coaching/invites/" + valid + "/accept").contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isConflict());
        var changed = body(call(owner, post("/api/v2/coaching/invites/" + valid + "/accept")
                .contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isOk()));
        assertThat(changed.get("coachId").asString()).isEqualTo(userId(stranger).toString());
        assertThat(changed.get("healthAccess").asBoolean()).isTrue();
        call(coach, get("/api/v2/web/profile").param("ownerId", userId(owner).toString()))
                .andExpect(status().isNotFound());
    }


    @Test
    void accountDeletionRequiresRecentLoginErasesOwnerDataAndTransfersCoachedPrograms() throws Exception {
        var ownProgram = UUID.randomUUID();
        publish(coach, "program", ownProgram, 1, fixture("program"));
        var token = invite(coach);
        accept(owner, token);
        var athleteId = userId(owner);
        var coachId = userId(coach);
        var coachedProgram = UUID.randomUUID();
        assertThat(push(coach, item("program", coachedProgram, athleteId, 0, 1, fixture("program")))
                .get("status").asString()).isEqualTo("accepted");
        call(coach, delete("/api/v1/me")).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/me").with(jwt().jwt(jwt -> jwt.issuer(ISSUER).subject(coach)
                .claim("auth_time", Instant.now().minusSeconds(600).getEpochSecond()))))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/me").with(jwt().jwt(jwt -> jwt.issuer(ISSUER).subject(coach)
                .claim("auth_time", Instant.now().getEpochSecond())))).andExpect(status().isAccepted());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sizylift.user_account WHERE id = ?", Integer.class,
                coachId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sizylift.program WHERE user_id = ?", Integer.class,
                coachId)).isZero();
        var transferred = body(call(owner, get("/api/v2/web/programs/" + coachedProgram))
                .andExpect(status().isOk()));
        assertThat(transferred.get("authorId").isNull()).isTrue();
        call(coach, get("/api/v1/me")).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sizylift.user_account WHERE issuer = ? AND subject = ?",
                Integer.class, ISSUER, coach)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sizylift.program WHERE user_id = ?", Integer.class,
                athleteId)).isEqualTo(1);
    }

    @Test
    void unavailableDeletionDoesNotEraseAnyData() throws Exception {
        var id = UUID.randomUUID();
        publish(owner, "program", id, 1, fixture("program"));
        org.mockito.Mockito.doThrow(new com.sizylift.backend.identity.deletion.KeycloakAccountDeletion.Unavailable())
                .when(accountDeletion).requireConfigured();
        mvc.perform(delete("/api/v1/me").with(jwt().jwt(jwt -> jwt.issuer(ISSUER).subject(owner)
                .claim("auth_time", Instant.now().getEpochSecond())))).andExpect(status().isServiceUnavailable());
        call(owner, get("/api/v2/web/programs/" + id)).andExpect(status().isOk());
    }

    @Test
    void rosterListsOnlyActiveAthletes() throws Exception {
        var link = accept(owner, invite(coach));
        publish(owner, "workout", UUID.randomUUID(), 1, fixture("workout"));
        var roster = body(call(coach, get("/api/v2/web/athletes")).andExpect(status().isOk()));
        assertThat(roster.size()).isEqualTo(1);
        assertThat(roster.get(0).get("workoutTitle").asString()).isEqualTo("Присед и жим");
        assertThat(body(call(stranger, get("/api/v2/web/athletes")).andExpect(status().isOk())).size()).isZero();
        call(coach, post("/api/v2/web/athletes/" + link + "/seen")).andExpect(status().isNotFound());
        call(owner, delete("/api/v2/coaching/links/" + link)).andExpect(status().isNoContent());
        assertThat(body(call(coach, get("/api/v2/web/athletes")).andExpect(status().isOk())).size()).isZero();
    }

    @Test
    void sheetImportRejectsNonGoogleUrlsBeforeFetching() throws Exception {
        for (var url : List.of("http://localhost:8080/actuator/health", "https://docs.google.com.evil.test/spreadsheets/d/x",
                "https://docs.google.com@localhost/spreadsheets/d/x")) {
            call(owner, post("/api/v2/web/import/sheet").contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("url", url)))).andExpect(status().isBadRequest());
        }
    }


    @Test
    void archivedCoachProgramTransfersToTheAthleteOnDisconnect() throws Exception {
        var link = accept(owner, invite(coach));
        var ownerId = userId(owner);
        var id = UUID.randomUUID();
        var saved = push(coach, item("program", id, ownerId, 0, 1, fixture("program")));
        call(coach, post("/api/v2/web/programs/" + id + "/archive").param("ownerId", ownerId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("revision", saved.get("revision").asLong()))))
                .andExpect(status().isNoContent());
        call(owner, delete("/api/v2/coaching/links/" + link)).andExpect(status().isNoContent());
        var archive = body(call(owner, get("/api/v2/web/programs/archive")).andExpect(status().isOk()));
        assertThat(archive.size()).isEqualTo(1);
        assertThat(archive.get(0).get("authorId").isNull()).isTrue();
        call(owner, post("/api/v2/web/programs/" + id + "/restore")).andExpect(status().isNoContent());
        call(coach, get("/api/v2/web/programs/archive").param("ownerId", ownerId.toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    void aNewerTombstoneCannotRestoreAnOldArchiveSnapshot() throws Exception {
        var id = UUID.randomUUID();
        publish(owner, "program", id, 1, fixture("program"));
        var revision = body(call(owner, get("/api/v2/web/programs/" + id)).andExpect(status().isOk()))
                .get("revision").asLong();
        call(owner, post("/api/v2/web/programs/" + id + "/archive").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("revision", revision)))).andExpect(status().isNoContent());
        var state = jdbc.queryForObject("SELECT revision FROM sizylift.published_aggregate WHERE user_id = ? AND type = 'program' AND aggregate_id = ?",
                Long.class, userId(owner), id);
        var newer = item("program", id, null, state, 1, null);
        newer.put("deleted", true);
        newer.put("changedAt", Instant.now().toString());
        assertThat(push(owner, newer).get("status").asString()).isEqualTo("accepted");
        call(owner, post("/api/v2/web/programs/" + id + "/restore")).andExpect(status().isConflict());
        assertThat(body(call(owner, get("/api/v2/web/programs/archive")).andExpect(status().isOk())).size()).isZero();
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

    @Test
    void standalonePlanHasNoProgramAndUsesRevisionsWithoutInventingCompletedWorkouts() throws Exception {
        var workout = (ObjectNode) fixture("program").get("cycles").get(0).get("weeks").get(0).get("workouts").get(0).deepCopy();
        var id = UUID.fromString(workout.get("id").asString());
        var path = "/api/v2/web/standalone-workouts/" + id;
        var first = Map.of("revision", 0, "workout", workout);
        mvc.perform(put(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(first)))
                .andExpect(status().isUnauthorized());
        var created = body(call(owner, put(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(first)))
                .andExpect(status().isOk()));
        var createdRevision = created.get("revision").asLong();
        assertThat(createdRevision).isPositive();
        assertThat(created.get("authorId").isNull()).isTrue();
        assertThat(body(call(owner, put(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(first)))
                .andExpect(status().isOk())).get("revision").asLong()).as("точный повтор").isEqualTo(createdRevision);
        assertThat(body(call(owner, get("/api/v2/web/programs")).andExpect(status().isOk())).get("programs")).isEmpty();
        var timeline = body(call(owner, get("/api/v2/web/timeline").param("from", "2026-10-01").param("to", "2026-10-31"))
                .andExpect(status().isOk()));
        assertThat(timeline.get("planned").get(0).get("programId").isNull()).isTrue();
        assertThat(timeline.get("planned").get(0).get("authorId").isNull()).isTrue();
        assertThat(timeline.get("workouts")).isEmpty();
        workout.put("workoutDate", "2026-10-22");
        var changed = Map.of("revision", createdRevision, "workout", workout);
        var changedRevision = body(call(owner, put(path).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(changed))).andExpect(status().isOk())).get("revision").asLong();
        assertThat(changedRevision).isGreaterThan(createdRevision);
        var stale = workout.deepCopy().put("title", "Устаревшая правка");
        call(owner, put(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("revision", createdRevision, "workout", stale))))
                .andExpect(status().isConflict());
        call(owner, delete(path).param("revision", String.valueOf(createdRevision))).andExpect(status().isConflict());
        var after = body(call(owner, get("/api/v2/web/timeline").param("from", "2026-10-22").param("to", "2026-10-22"))
                .andExpect(status().isOk())).get("planned").get(0);
        assertThat(after.get("scheduledDate").asString()).isEqualTo("2026-10-22");
        assertThat(after.get("rescheduledAt").isNull()).isFalse();
        call(owner, delete(path).param("revision", String.valueOf(changedRevision))).andExpect(status().isNoContent());
        call(owner, get(path)).andExpect(status().isNotFound());
        call(owner, put(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(first))).andExpect(status().isConflict());
    }

    @Test
    void ownerStandalonePlanIsReadOnlyToActiveCoachAndUnavailableToStrangersOrFormerCoach() throws Exception {
        var workout = fixture("program").get("cycles").get(0).get("weeks").get(0).get("workouts").get(0);
        var path = "/api/v2/web/standalone-workouts/" + workout.get("id").asString();
        var request = json.writeValueAsString(Map.of("revision", 0, "workout", workout));
        var revision = body(call(owner, put(path).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())).get("revision").asLong();
        var ownerId = userId(owner).toString();
        var linkId = accept(owner, invite(coach));
        call(coach, get(path).param("ownerId", ownerId)).andExpect(status().isOk());
        call(stranger, get(path).param("ownerId", ownerId)).andExpect(status().isNotFound());
        call(stranger, get(path)).andExpect(status().isNotFound());
        for (var actor : List.of(coach, stranger)) {
            call(actor, put(path).param("ownerId", ownerId).contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isForbidden());
            call(actor, delete(path).param("ownerId", ownerId).param("revision", "1")).andExpect(status().isForbidden());
        }
        call(owner, delete("/api/v2/coaching/links/" + linkId)).andExpect(status().isNoContent());
        call(coach, get(path).param("ownerId", ownerId)).andExpect(status().isNotFound());
        assertThat(body(call(owner, get(path)).andExpect(status().isOk())).get("revision").asLong()).isEqualTo(revision);
    }

    @Test
    void activeCoachPlansAStandaloneWorkoutForTheAthlete() throws Exception {
        var workout = (ObjectNode) fixture("program").get("cycles").get(0).get("weeks").get(0).get("workouts").get(0).deepCopy();
        var id = UUID.randomUUID();
        workout.put("id", id.toString());
        var path = "/api/v2/web/standalone-workouts/" + id;
        var ownerId = userId(owner).toString();
        accept(owner, invite(coach));
        var request = json.writeValueAsString(Map.of("revision", 0, "workout", workout));
        var created = body(call(coach, put(path).param("ownerId", ownerId).contentType(MediaType.APPLICATION_JSON)
                .content(request)).andExpect(status().isOk()));
        assertThat(created.get("ownerId").asString()).isEqualTo(ownerId);
        var coachId = userId(coach).toString();
        assertThat(created.get("authorId").asString()).isEqualTo(coachId);
        assertThat(body(call(coach, get(path).param("ownerId", ownerId)).andExpect(status().isOk()))
                .get("authorId").asString()).isEqualTo(coachId);
        var planned = body(call(coach, get("/api/v2/web/timeline").param("ownerId", ownerId)
                .param("from", "2026-10-01").param("to", "2026-10-31")).andExpect(status().isOk()))
                .get("planned").get(0);
        assertThat(planned.get("authorId").asString()).isEqualTo(coachId);
        assertThat(planned.get("programId").isNull()).isTrue();
        var revision = created.get("revision").asLong();
        call(owner, put(path).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("revision", revision, "workout", workout))))
                .andExpect(status().isForbidden());
        call(coach, delete(path).param("ownerId", ownerId).param("revision", String.valueOf(revision)))
                .andExpect(status().isNoContent());
    }

    @Test
    void standalonePlanValidatesNestedSetsAndIdentity() throws Exception {
        var workout = (ObjectNode) fixture("program").get("cycles").get(0).get("weeks").get(0).get("workouts").get(0).deepCopy();
        var path = "/api/v2/web/standalone-workouts/" + workout.get("id").asString();
        var wrongId = "/api/v2/web/standalone-workouts/" + UUID.randomUUID();
        call(owner, put(path).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("workout", workout)))).andExpect(status().isBadRequest());
        var valid = json.writeValueAsString(Map.of("revision", 0, "workout", workout));
        call(owner, put(wrongId).contentType(MediaType.APPLICATION_JSON).content(valid)).andExpect(status().isBadRequest());
        ((ObjectNode) workout.get("blocks").get(0).get("exercises").get(0).get("sets").get(0)).put("repetitions", -1);
        call(owner, put(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("revision", 0, "workout", workout))))
                .andExpect(status().isBadRequest());
        call(owner, get(path)).andExpect(status().isNotFound());
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
