package com.sizylift.backend.library;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.sizylift.backend.CoachWorkspaceApiSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Папки и порядок шаблонов тренера: запись целиком, проверка формы, у каждого — своя библиотека. */
@SpringBootTest
@AutoConfigureMockMvc
class ProgramLibraryApiTest extends CoachWorkspaceApiSupport {

    static final String PATH = "/api/v2/web/program-library";

    @Test
    void emptyByDefaultThenReplacedWholeAndKeptPerUser() throws Exception {
        var coach = newSubject();
        var other = newSubject();
        var empty = body(call(coach, get(PATH)).andExpect(status().isOk()));
        assertThat(empty.get("folders")).isEmpty();
        assertThat(empty.get("entries")).isEmpty();

        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        var saved = body(putJson(coach, Map.of(
                "folders", List.of(" Пауэрлифтинг ", "Новички"),
                "entries", List.of(
                        Map.of("programId", second, "folder", "Новички"),
                        new java.util.HashMap<>(Map.of("programId", first))))).andExpect(status().isOk()));
        assertThat(saved.get("folders").get(0).asString()).isEqualTo("Пауэрлифтинг");
        assertThat(saved.get("entries").get(0).get("programId").asString()).isEqualTo(second.toString());
        assertThat(saved.get("entries").get(0).get("folder").asString()).isEqualTo("Новички");
        assertThat(saved.get("entries").get(1).get("folder").isNull()).isTrue();

        putJson(coach, Map.of("folders", List.of(), "entries", List.of(Map.of("programId", first))))
                .andExpect(status().isOk());
        var replaced = body(call(coach, get(PATH)));
        assertThat(replaced.get("folders")).isEmpty();
        assertThat(replaced.get("entries")).hasSize(1);

        assertThat(body(call(other, get(PATH))).get("entries")).isEmpty();
    }

    @Test
    void rejectsDuplicatesUnknownFoldersAndLongNames() throws Exception {
        var coach = newSubject();
        var id = UUID.randomUUID();
        putJson(coach, Map.of("folders", List.of("А", "А"), "entries", List.of())).andExpect(status().isBadRequest());
        putJson(coach, Map.of("folders", List.of("я".repeat(81)), "entries", List.of()))
                .andExpect(status().isBadRequest());
        putJson(coach, Map.of("folders", List.of(), "entries", List.of(Map.of("programId", id, "folder", "Нет"))))
                .andExpect(status().isBadRequest());
        putJson(coach, Map.of("folders", List.of(), "entries", List.of(Map.of("programId", id), Map.of("programId", id))))
                .andExpect(status().isBadRequest());
    }

    private org.springframework.test.web.servlet.ResultActions putJson(String subject, Object body) throws Exception {
        return call(subject, put(PATH).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }
}
