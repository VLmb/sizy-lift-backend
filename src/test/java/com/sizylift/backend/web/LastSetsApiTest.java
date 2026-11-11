package com.sizylift.backend.web;

import java.util.UUID;

import com.sizylift.backend.CoachWorkspaceApiSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Последний факт по упражнению раньше даты — колонка «Прошлый раз» редактора (docs/web.md). */
@SpringBootTest
@AutoConfigureMockMvc
class LastSetsApiTest extends CoachWorkspaceApiSupport {

    static final String SQUAT = "14000000-0000-4000-8000-000000000001";

    String coach;
    String athlete;
    UUID athleteId;

    @BeforeEach
    void linked() throws Exception {
        coach = newSubject();
        athlete = newSubject();
        athleteId = userId(athlete);
        link(coach, athlete);
    }

    @Test
    void returnsTheLatestWorkoutBeforeTheDateWithAllSetsInOrder() throws Exception {
        var older = UUID.randomUUID();
        push(athlete, item("workout", older, null, 0, 1, dated("2026-10-07")));
        var latest = UUID.randomUUID();
        var payload = dated("2026-10-14");
        ((ObjectNode) payload.get("blocks").get(0).get("exercises").get(0).get("sets").get(1))
                .put("completionStatus", "skipped");
        push(athlete, item("workout", latest, null, 0, 1, payload));
        push(athlete, item("workout", UUID.randomUUID(), null, 0, 1, dated("2026-10-21")));

        var entry = only(lastSets(coach, "?ownerId=" + athleteId + "&before=2026-10-21&exerciseId=" + SQUAT));
        assertThat(entry.get("workoutId").asString()).isEqualTo(latest.toString());
        assertThat(entry.get("workoutDate").asString()).isEqualTo("2026-10-14");
        var sets = entry.get("sets");
        assertThat(sets.get(0).get("setType").asString()).isEqualTo("warmup");
        assertThat(sets.get(0).get("weightGrams").asInt()).isEqualTo(60000);
        assertThat(sets.get(1).get("completionStatus").asString()).isEqualTo("skipped");
        assertThat(sets.get(1).get("rpeTenths").asInt()).isEqualTo(85);

        var strictlyBefore = only(lastSets(coach, "?ownerId=" + athleteId + "&before=2026-10-14&exerciseId=" + SQUAT));
        assertThat(strictlyBefore.get("workoutId").asString()).isEqualTo(older.toString());
    }

    @Test
    void exerciseWithoutFactIsAbsentAndOwnDataNeedsNoOwner() throws Exception {
        push(coach, item("workout", UUID.randomUUID(), null, 0, 1, dated("2026-10-14")));

        assertThat(lastSets(coach, "?before=2026-10-20&exerciseId=" + SQUAT + "&exerciseId=" + UUID.randomUUID())
                .get("entries")).hasSize(1);
        assertThat(lastSets(coach, "?before=2026-10-14&exerciseId=" + SQUAT).get("entries")).isEmpty();
    }

    @Test
    void strangersGetNotFoundAndEmptyListIsRejected() throws Exception {
        call(newSubject(), get("/api/v2/web/last-sets?ownerId=" + athleteId + "&before=2026-10-20&exerciseId=" + SQUAT))
                .andExpect(status().isNotFound());
        call(coach, get("/api/v2/web/last-sets?before=2026-10-20&exerciseId=")).andExpect(status().isBadRequest());
        call(coach, get("/api/v2/web/last-sets?exerciseId=" + SQUAT)).andExpect(status().isBadRequest());
    }

    private ObjectNode dated(String date) throws Exception {
        var payload = workout();
        payload.put("workoutDate", date);
        return payload;
    }

    private JsonNode lastSets(String subject, String query) throws Exception {
        return body(call(subject, get("/api/v2/web/last-sets" + query)).andExpect(status().isOk()));
    }

    private static JsonNode only(JsonNode response) {
        assertThat(response.get("entries")).hasSize(1);
        return response.get("entries").get(0);
    }
}
