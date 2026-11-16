package com.sizylift.backend.video;

import java.util.Map;
import java.util.UUID;

import com.sizylift.backend.CoachWorkspaceApiSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Флаг функции {@code sizylift.video.enabled=false}: клиенты узнают о нём из {@code /me}, сервер видео не выдаёт. */
@SpringBootTest(properties = "sizylift.video.enabled=false")
@AutoConfigureMockMvc
class VideoDisabledApiTest extends CoachWorkspaceApiSupport {

    static final String DISABLED = "video_disabled";

    @Test
    void currentUserAdvertisesTheFlagAndVideosAreNeitherServedNorAccepted() throws Exception {
        var athlete = newSubject();
        call(athlete, get("/api/v1/me")).andExpect(status().isOk())
                .andExpect(jsonPath("$.videoAvailable").value(false));

        var id = UUID.randomUUID();
        postJson(athlete, "/api/v2/videos", Map.of("id", id, "kind", "set", "sizeBytes", 1000,
                "previewSizeBytes", 100, "durationMs", 1000))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(DISABLED));
        call(athlete, post("/api/v2/videos/" + id + "/complete")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(DISABLED));
        call(athlete, get("/api/v2/videos/" + id)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(DISABLED));
        call(athlete, get("/api/v2/videos").param("kind", "set")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(DISABLED));
        // Удаление не закрыто флагом: незнакомый ролик — обычное «не найдено».
        call(athlete, delete("/api/v2/videos/" + id)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"));
    }
}
