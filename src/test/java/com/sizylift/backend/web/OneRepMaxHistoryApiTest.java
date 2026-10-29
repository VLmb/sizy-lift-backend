package com.sizylift.backend.web;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.sizylift.backend.CoachWorkspaceApiSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** История 1ПМ (docs/web.md): пополнение из v1 и v2, порядок, права, удаление агрегата и аккаунта. */
@SpringBootTest
@AutoConfigureMockMvc
class OneRepMaxHistoryApiTest extends CoachWorkspaceApiSupport {

    static final UUID SQUAT = UUID.fromString("14000000-0000-4000-8000-000000000001");

    String athlete;
    UUID athleteId;

    @BeforeEach
    void athlete() throws Exception {
        athlete = newSubject();
        athleteId = userId(athlete);
    }

    @Test
    void everyNewValueFromV1AndV2AddsAnEntryNewestFirst() throws Exception {
        legacy(athlete, "one_rep_max", SQUAT, 1, oneRepMax(160000, "2026-09-20", "2026-09-20T18:00:00Z"));
        legacy(athlete, "one_rep_max", SQUAT, 2, oneRepMax(160000, "2026-09-20", "2026-09-21T18:00:00Z"));
        var revision = jdbc.queryForObject("SELECT revision FROM sizylift.published_aggregate WHERE user_id = ? "
                + "AND type = 'one_rep_max' AND aggregate_id = ?", Long.class, athleteId, SQUAT);
        push(athlete, item("one_rep_max", SQUAT, null, revision, 1,
                oneRepMax(170000, "2026-10-10", "2026-10-20T10:00:00Z")));

        var entries = history(athlete, "");
        assertThat(entries).as("повтор того же значения новой точки не даёт").hasSize(2);
        assertThat(entries.get(0).get("weightGrams").asInt()).isEqualTo(170000);
        assertThat(entries.get(0).get("achievedOn").asString()).isEqualTo("2026-10-10");
        assertThat(entries.get(0).get("recordedAt").isNull()).isFalse();
        assertThat(entries.get(1).get("weightGrams").asInt()).isEqualTo(160000);
        assertThat(history(athlete, "", UUID.randomUUID())).isEmpty();
    }

    @Test
    void coachReadsTheAthleteHistoryOnlyWhileLinked() throws Exception {
        var coach = newSubject();
        var stranger = newSubject();
        var linkId = link(coach, athlete);
        legacy(athlete, "one_rep_max", SQUAT, 1, oneRepMax(160000, "2026-09-20", "2026-09-20T18:00:00Z"));

        assertThat(history(coach, "?ownerId=" + athleteId)).hasSize(1);
        call(stranger, get(path(SQUAT) + "?ownerId=" + athleteId)).andExpect(status().isNotFound());

        call(coach, delete("/api/v2/coaching/links/" + linkId)).andExpect(status().isNoContent());
        call(coach, get(path(SQUAT) + "?ownerId=" + athleteId)).andExpect(status().isNotFound());
    }

    @Test
    void deletingTheAggregateOrTheAccountRemovesTheHistory() throws Exception {
        legacy(athlete, "one_rep_max", SQUAT, 1, oneRepMax(160000, "2026-09-20", "2026-09-20T18:00:00Z"));
        legacy(athlete, "one_rep_max", SQUAT, 2, oneRepMax(165000, "2026-09-27", "2026-09-27T18:00:00Z"));
        legacy(athlete, "one_rep_max", SQUAT, 3, null);
        assertThat(history(athlete, "")).isEmpty();

        legacy(athlete, "one_rep_max", SQUAT, 4, oneRepMax(170000, "2026-10-10", "2026-10-10T18:00:00Z"));
        assertThat(history(athlete, "")).hasSize(1);

        jdbc.update("DELETE FROM sizylift.user_account WHERE id = ?", athleteId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sizylift.one_rep_max_history WHERE user_id = ?",
                Long.class, athleteId)).isZero();
    }

    private ObjectNode oneRepMax(int weightGrams, String achievedOn, String updatedAt) {
        return json.createObjectNode().put("weightGrams", weightGrams).put("achievedOn", achievedOn)
                .put("updatedAt", updatedAt);
    }

    private List<JsonNode> history(String subject, String query) throws Exception {
        return history(subject, query, SQUAT);
    }

    private List<JsonNode> history(String subject, String query, UUID exerciseId) throws Exception {
        var entries = new ArrayList<JsonNode>();
        body(call(subject, get(path(exerciseId) + query)).andExpect(status().isOk())).get("entries")
                .forEach(entries::add);
        return entries;
    }

    private static String path(UUID exerciseId) {
        return "/api/v2/web/one-rep-maxes/" + exerciseId + "/history";
    }
}
