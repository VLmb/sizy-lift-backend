package com.sizylift.backend.tasks;

import java.util.ArrayList;
import java.util.HashMap;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Задачи тренера (docs/coach-workspace.md, раздел 5): правка, порядок, доступ только по активной связи. */
@SpringBootTest
@AutoConfigureMockMvc
class CoachTaskApiTest extends CoachWorkspaceApiSupport {

    static final int MAX_TEXT = 500;

    String coach;
    String athlete;
    UUID athleteId;
    UUID linkId;

    @BeforeEach
    void linked() throws Exception {
        coach = newSubject();
        athlete = newSubject();
        athleteId = userId(athlete);
        linkId = link(coach, athlete);
    }

    @Test
    void createsTrimmedTaskAndValidatesTheText() throws Exception {
        var task = create(athleteId, "  Позвонить про сон  ", "2026-11-01");

        assertThat(task.get("text").asString()).isEqualTo("Позвонить про сон");
        assertThat(task.get("athleteId").asString()).isEqualTo(athleteId.toString());
        assertThat(task.get("dueOn").asString()).isEqualTo("2026-11-01");
        assertThat(task.get("doneAt").isNull()).isTrue();
        assertThat(task.get("createdAt").isNull()).isFalse();

        postJson(coach, "/api/v2/web/tasks", request(athleteId, "   ", null)).andExpect(status().isBadRequest());
        postJson(coach, "/api/v2/web/tasks", request(athleteId, "я".repeat(MAX_TEXT + 1), null))
                .andExpect(status().isBadRequest());
        postJson(coach, "/api/v2/web/tasks", request(null, "Текст", null)).andExpect(status().isBadRequest());
        create(athleteId, "я".repeat(MAX_TEXT), null);
    }

    @Test
    void ordersOpenByDueDateThenCreationAndDoneByCompletion() throws Exception {
        var later = id(create(athleteId, "Позже", "2026-11-05"));
        var noDue = id(create(athleteId, "Без срока", null));
        var sooner = id(create(athleteId, "Раньше", "2026-11-01"));
        var noDueSecond = id(create(athleteId, "Без срока 2", null));
        var doneFirst = id(create(athleteId, "Сделано первым", null));
        var doneSecond = id(create(athleteId, "Сделано вторым", "2026-10-01"));
        update(doneFirst, Map.of("done", true));
        update(doneSecond, Map.of("done", true));

        assertThat(ids(list("?athleteId=" + athleteId))).containsExactly(sooner, later, noDue, noDueSecond);
        assertThat(ids(list(""))).containsExactly(sooner, later, noDue, noDueSecond);
        assertThat(ids(list("?status=done"))).containsExactly(doneSecond, doneFirst);
        assertThat(ids(list("?status=all")))
                .containsExactly(sooner, later, noDue, noDueSecond, doneSecond, doneFirst);
        call(coach, get("/api/v2/web/tasks?status=later")).andExpect(status().isBadRequest());
    }

    @Test
    void patchChangesOnlyGivenFieldsAndNullClearsTheDueDate() throws Exception {
        var task = id(create(athleteId, "Проверить дневник", "2026-11-01"));

        var renamed = update(task, Map.of("text", " Проверить питание "));
        assertThat(renamed.get("text").asString()).isEqualTo("Проверить питание");
        assertThat(renamed.get("dueOn").asString()).isEqualTo("2026-11-01");

        var body = new HashMap<String, Object>();
        body.put("dueOn", null);
        var cleared = update(task, body);
        assertThat(cleared.get("dueOn").isNull()).isTrue();
        assertThat(cleared.get("text").asString()).isEqualTo("Проверить питание");

        var done = update(task, Map.of("done", true));
        assertThat(done.get("doneAt").isNull()).isFalse();
        assertThat(update(task, Map.of("done", true)).get("doneAt").asString())
                .as("повторное «сделано» не двигает время").isEqualTo(done.get("doneAt").asString());
        assertThat(update(task, Map.of("done", false)).get("doneAt").isNull()).isTrue();

        patchRaw(coach, task, "{\"text\":\"\"}").andExpect(status().isBadRequest());
        patchRaw(coach, task, "{\"dueOn\":\"завтра\"}").andExpect(status().isBadRequest());
        patchRaw(coach, task, "{\"done\":\"yes\"}").andExpect(status().isBadRequest());
    }

    @Test
    void deleteRemovesTheTask() throws Exception {
        var task = id(create(athleteId, "Удалить", null));

        call(coach, delete("/api/v2/web/tasks/" + task)).andExpect(status().isNoContent());
        call(coach, delete("/api/v2/web/tasks/" + task)).andExpect(status().isNotFound());
        assertThat(list("").size()).isZero();
    }

    @Test
    void foreignTasksAndAthletesWithoutAnActiveLinkAreNotFound() throws Exception {
        var task = id(create(athleteId, "Только моя", null));
        var stranger = newSubject();

        assertThat(body(call(stranger, get("/api/v2/web/tasks")).andExpect(status().isOk())).get("tasks").size())
                .isZero();
        call(stranger, get("/api/v2/web/tasks?athleteId=" + athleteId)).andExpect(status().isNotFound());
        postJson(stranger, "/api/v2/web/tasks", request(athleteId, "Чужое", null)).andExpect(status().isNotFound());
        patchRaw(stranger, task, "{\"done\":true}").andExpect(status().isNotFound());
        call(stranger, delete("/api/v2/web/tasks/" + task)).andExpect(status().isNotFound());
        postJson(athlete, "/api/v2/web/tasks", request(athleteId, "Себе", null)).andExpect(status().isNotFound());

        call(coach, delete("/api/v2/coaching/links/" + linkId)).andExpect(status().isNoContent());

        assertThat(list("").size()).isZero();
        call(coach, get("/api/v2/web/tasks?athleteId=" + athleteId)).andExpect(status().isNotFound());
        patchRaw(coach, task, "{\"done\":true}").andExpect(status().isNotFound());
        postJson(coach, "/api/v2/web/tasks", request(athleteId, "После разрыва", null))
                .andExpect(status().isNotFound());
    }

    private JsonNode create(UUID athlete, String text, String dueOn) throws Exception {
        return body(postJson(coach, "/api/v2/web/tasks", request(athlete, text, dueOn))
                .andExpect(status().isCreated()));
    }

    private static Map<String, Object> request(UUID athlete, String text, String dueOn) {
        var request = new HashMap<String, Object>();
        request.put("athleteId", athlete);
        request.put("text", text);
        request.put("dueOn", dueOn);
        return request;
    }

    private JsonNode update(String task, Map<String, Object> body) throws Exception {
        return body(patchRaw(coach, task, json.writeValueAsString(body)).andExpect(status().isOk()));
    }

    private org.springframework.test.web.servlet.ResultActions patchRaw(String subject, String task, String body)
            throws Exception {
        return call(subject, patch("/api/v2/web/tasks/" + task).contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private JsonNode list(String query) throws Exception {
        return body(call(coach, get("/api/v2/web/tasks" + query)).andExpect(status().isOk())).get("tasks");
    }

    private static String id(JsonNode task) {
        return task.get("id").asString();
    }

    private static List<String> ids(JsonNode tasks) {
        var ids = new ArrayList<String>();
        tasks.forEach(task -> ids.add(id(task)));
        return ids;
    }
}
