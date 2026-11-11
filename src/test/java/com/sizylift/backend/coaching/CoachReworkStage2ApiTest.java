package com.sizylift.backend.coaching;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.sizylift.backend.CoachWorkspaceApiSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Переделка тренерского режима, этап 2 (docs/coach-workspace.md, раздел 8): приглашение с программой до входа,
 * тихий черновик тренера, назначение нескольким и карточка подопечного.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CoachReworkStage2ApiTest extends CoachWorkspaceApiSupport {

    static final UUID TEMPLATE = UUID.fromString("00000000-0000-4000-8000-00000000c013");

    String coach;
    String athlete;
    UUID coachId;
    UUID athleteId;

    @BeforeEach
    void accounts() throws Exception {
        coach = newSubject();
        athlete = newSubject();
        coachId = userId(coach);
        athleteId = userId(athlete);
        call(coach, put("/api/v2/coaching/coach-mode").contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}")).andExpect(status().isOk());
    }

    @Test
    void inviteWithProgramShowsTheFirstWeekWithoutLoginAndCopiesASilentDraftOnAccept() throws Exception {
        var template = UUID.randomUUID();
        push(coach, item("program", template, null, 0, 1, withFreshIds("program")));
        var token = invite(Map.of("programId", template));

        var page = body(mvc.perform(get("/api/v2/coaching/invites/" + token + "/public")).andExpect(status().isOk()));
        assertThat(page.has("coachId")).as("ничего лишнего").isFalse();
        assertThat(page.has("expiresAt")).isFalse();
        assertThat(page.get("coachSees")).isNotEmpty();
        var program = page.get("program");
        assertThat(program.get("weeks").asInt()).isEqualTo(1);
        assertThat(program.get("workoutsPerWeek").asInt()).isEqualTo(1);
        var day = program.get("firstWeek").get(0);
        assertThat(day.get("dayOfWeek").asInt()).as("2026-10-14 — среда").isEqualTo(3);
        assertThat(day.get("title").asString()).isEqualTo("День A");
        assertThat(day.get("exercises")).isNotEmpty();
        assertThat(page.toString()).doesNotContain("targetWeightGrams").doesNotContain("repetitions");

        accept(token);
        var copies = programs(athleteId);
        assertThat(copies).hasSize(1);
        var copy = copies.get(0);
        assertThat(copy.get("status").asString()).isEqualTo("draft");
        assertThat(copy.get("authorId").asString()).isEqualTo(coachId.toString());
        assertThat(copy.get("id").asString()).isNotEqualTo(template.toString());
        var full = body(call(coach, get("/api/v2/web/programs/" + copy.get("id").asString() + "?ownerId=" + athleteId)));
        assertThat(full.get("program").get("sourceProgramId").asString()).as("источник копии").isEqualTo(
                template.toString());
        assertThat(notifications(athleteId)).as("тихий черновик").doesNotContain("program_updated");

        mvc.perform(get("/api/v2/coaching/invites/" + token + "/public")).andExpect(status().isGone());
    }

    @Test
    void inviteWithoutProgramShowsOnlyTheCoachAndForeignProgramIsRejected() throws Exception {
        var token = invite(Map.of());
        var page = body(mvc.perform(get("/api/v2/coaching/invites/" + token + "/public")).andExpect(status().isOk()));
        assertThat(page.get("program").isNull()).isTrue();

        var foreign = UUID.randomUUID();
        push(athlete, item("program", foreign, null, 0, 1, withFreshIds("program")));
        postJson(coach, "/api/v2/coaching/invites", Map.of("programId", foreign)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v2/coaching/invites/not-a-token/public")).andExpect(status().isNotFound());
    }

    @Test
    void coachDraftIsSilentUntilActive() throws Exception {
        link(coach, athlete);
        var id = UUID.randomUUID();
        var draft = withFreshIds("program");
        draft.put("status", "draft");
        var written = push(coach, item("program", id, athleteId, 0, 1, draft));
        assertThat(notifications(athleteId)).doesNotContain("program_updated");

        draft.put("status", "active");
        push(coach, item("program", id, athleteId, written.get("revision").asLong(), 2, draft));
        assertThat(notifications(athleteId)).contains("program_updated");
    }

    @Test
    void assignCreatesDraftsForAllAthletesOrNone() throws Exception {
        link(coach, athlete);
        var second = newSubject();
        var secondId = userId(second);
        link(coach, second);
        var request = Map.of("copies", List.of(copy(athleteId), copy(secondId)));
        var created = body(postJson(coach, "/api/v2/web/programs/assign", request).andExpect(status().isCreated()));
        assertThat(created.get("created")).hasSize(2);
        assertThat(programs(athleteId)).hasSize(1);
        assertThat(programs(secondId).get(0).get("status").asString()).isEqualTo("draft");
        assertThat(programs(secondId).get(0).get("sourceProgramId").asString()).as("«уже есть» в списке")
                .isEqualTo(TEMPLATE.toString());

        var stranger = userId(newSubject());
        postJson(coach, "/api/v2/web/programs/assign", Map.of("copies", List.of(copy(athleteId), copy(stranger))))
                .andExpect(status().isConflict());
        assertThat(programs(athleteId)).as("ни одной копии при отказе").hasSize(1);

        var active = copy(athleteId);
        ((ObjectNode) active.get("program")).put("status", "active");
        postJson(coach, "/api/v2/web/programs/assign", Map.of("copies", List.of(active)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cardIsTheCoachsOnlyAndGoneAfterTheLinkEnds() throws Exception {
        var linkId = link(coach, athlete);
        var path = "/api/v2/coaching/links/" + linkId + "/card";
        var empty = body(call(coach, get(path)).andExpect(status().isOk()));
        assertThat(empty.get("targetExerciseIds").isNull()).as("тренер ещё не выбирал").isTrue();

        var squat = UUID.fromString("14000000-0000-4000-8000-000000000001");
        var saved = body(call(coach, put(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(
                Map.of("notes", "  Колено: без глубокого приседа  ", "startDate", "2027-01-16",
                        "startName", "Кубок города", "targetExerciseIds", List.of(squat, squat)))))
                .andExpect(status().isOk()));
        assertThat(saved.get("notes").asString()).isEqualTo("Колено: без глубокого приседа");
        assertThat(saved.get("startDate").asString()).isEqualTo("2027-01-16");
        assertThat(saved.get("targetExerciseIds")).hasSize(1);

        call(coach, put(path).contentType(MediaType.APPLICATION_JSON).content("{\"targetExerciseIds\":[]}"))
                .andExpect(status().isOk());
        assertThat(body(call(coach, get(path))).get("targetExerciseIds")).as("пустой список допустим").isEmpty();

        call(athlete, get(path)).andExpect(status().isNotFound());
        call(coach, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                "/api/v2/coaching/links/" + linkId)).andExpect(status().isNoContent());
        call(coach, get(path)).andExpect(status().isNotFound());
    }

    private Map<String, Object> copy(UUID athlete) throws Exception {
        var program = withFreshIds("program");
        program.put("status", "draft");
        program.put("sourceProgramId", TEMPLATE.toString());
        return Map.of("athleteId", athlete, "id", UUID.randomUUID(), "program", program);
    }

    private String invite(Map<String, Object> request) throws Exception {
        return body(postJson(coach, "/api/v2/coaching/invites", request).andExpect(status().isCreated()))
                .get("token").asString();
    }

    private void accept(String token) throws Exception {
        call(athlete, post("/api/v2/coaching/invites/" + token + "/accept")).andExpect(status().isOk());
    }

    private List<JsonNode> programs(UUID owner) throws Exception {
        var list = body(call(coach, get("/api/v2/web/programs?ownerId=" + owner)).andExpect(status().isOk()));
        var result = new java.util.ArrayList<JsonNode>();
        list.get("programs").forEach(result::add);
        return result;
    }

    private List<String> notifications(UUID user) {
        return jdbc.queryForList("SELECT kind FROM sizylift.notification_outbox WHERE user_id = ?", String.class,
                user);
    }
}
