package com.sizylift.backend.video;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.sizylift.backend.CoachWorkspaceApiSupport;
import com.sizylift.backend.sync.service.CoachExerciseCopies;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Видео техники у упражнения (docs/video.md, «Видео упражнения»): одно на упражнение, видят все действующие
 * подопечные; упражнение из библиотеки тренера у подопечного — копия под производным id. Переименование ролика.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ExerciseVideoApiTest extends CoachWorkspaceApiSupport {

    static final UUID SQUAT = UUID.fromString("11000000-0000-4000-8000-000000000010");
    static final int VIDEO_BYTES = 1024 * 1024;
    static final int PREVIEW_BYTES = 10_000;
    static final int DURATION_MS = 24_000;
    static final HttpClient HTTP = HttpClient.newHttpClient();

    String coach;
    String athlete;
    UUID coachId;
    UUID athleteId;
    UUID linkId;

    @BeforeEach
    void accounts() throws Exception {
        coach = newSubject();
        athlete = newSubject();
        coachId = userId(coach);
        athleteId = userId(athlete);
        linkId = link(coach, athlete);
    }

    @Test
    void everyActiveAthleteSeesTheCoachVideoOfAnExercise() throws Exception {
        var video = uploaded(coach, "technique", "Присед: вид сбоку");
        var other = newSubject();
        link(coach, other);
        var stranger = newSubject();

        // До прикрепления видео тренера подопечному не видно.
        call(athlete, get("/api/v2/videos/" + video)).andExpect(status().isNotFound());
        attach(coach, SQUAT, video).andExpect(status().isOk()).andExpect(jsonPath("$.exerciseId").value(SQUAT.toString()))
                .andExpect(jsonPath("$.own").value(true)).andExpect(jsonPath("$.previewUrl").isNotEmpty());

        for (var subject : new String[] {athlete, other}) {
            var item = body(call(subject, get("/api/v2/videos/exercises")).andExpect(status().isOk()))
                    .get("exercises").get(0);
            assertThat(item.get("exerciseId").asString()).isEqualTo(SQUAT.toString());
            assertThat(item.get("videoId").asString()).isEqualTo(video.toString());
            assertThat(item.get("title").asString()).isEqualTo("Присед: вид сбоку");
            assertThat(item.get("own").asBoolean()).isFalse();
            call(subject, get("/api/v2/videos/" + video)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.videoUrl").isNotEmpty());
        }
        call(stranger, get("/api/v2/videos/" + video)).andExpect(status().isNotFound());
        assertThat(body(call(stranger, get("/api/v2/videos/exercises"))).get("exercises")).isEmpty();

        call(athlete, delete("/api/v2/coaching/links/" + linkId)).andExpect(status().isNoContent());
        assertThat(body(call(athlete, get("/api/v2/videos/exercises"))).get("exercises")).as("связь разорвана")
                .isEmpty();
        call(athlete, get("/api/v2/videos/" + video)).andExpect(status().isNotFound());
    }

    @Test
    void coachOwnExerciseReachesTheAthleteAsItsCopy() throws Exception {
        var exercise = UUID.randomUUID();
        push(coach, item("exercise", exercise, null, 0, 1, fixture("exercise")));
        var video = uploaded(coach, "technique", "Тяга: старт");

        attach(coach, exercise, video).andExpect(status().isOk());

        var item = body(call(athlete, get("/api/v2/videos/exercises"))).get("exercises").get(0);
        assertThat(item.get("exerciseId").asString())
                .isEqualTo(CoachExerciseCopies.copyId(athleteId, exercise).toString());
        var own = body(call(coach, get("/api/v2/videos/exercises"))).get("exercises").get(0);
        assertThat(own.get("exerciseId").asString()).as("у тренера — своё упражнение").isEqualTo(exercise.toString());
    }

    @Test
    void oneVideoPerExerciseReplacedOrRemoved() throws Exception {
        var first = uploaded(coach, "technique", "Первое");
        var second = uploaded(coach, "technique", "Второе");
        attach(coach, SQUAT, first).andExpect(status().isOk());
        attach(coach, SQUAT, second).andExpect(status().isOk());

        var items = body(call(athlete, get("/api/v2/videos/exercises"))).get("exercises");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("videoId").asString()).isEqualTo(second.toString());
        // Заменённое видео подопечному больше не открыто.
        call(athlete, get("/api/v2/videos/" + first)).andExpect(status().isNotFound());

        call(coach, delete("/api/v2/videos/exercises/" + SQUAT)).andExpect(status().isNoContent());
        call(coach, delete("/api/v2/videos/exercises/" + SQUAT)).andExpect(status().isNotFound());
        assertThat(body(call(athlete, get("/api/v2/videos/exercises"))).get("exercises")).isEmpty();
    }

    @Test
    void onlyTheCoachAttachesOwnTechniqueVideos() throws Exception {
        var setVideo = uploaded(athlete, "set", null);
        var technique = uploaded(coach, "technique", "Техника");

        attach(athlete, SQUAT, setVideo).andExpect(status().isBadRequest());
        attach(athlete, SQUAT, technique).andExpect(status().isNotFound());
        attach(coach, SQUAT, UUID.randomUUID()).andExpect(status().isNotFound());
    }

    @Test
    void deletingAVideoTakesItOffExercisesAndSaysSo() throws Exception {
        var video = uploaded(coach, "technique", "Присед");
        attach(coach, SQUAT, video).andExpect(status().isOk());
        call(coach, get("/api/v2/videos/" + video)).andExpect(jsonPath("$.usage.exercises[0].exerciseName")
                .value("Присед со штангой"));

        var deleted = body(call(coach, delete("/api/v2/videos/" + video)).andExpect(status().isOk()));

        assertThat(deleted.get("removedFrom").get("exercises").get(0).get("exerciseId").asString())
                .isEqualTo(SQUAT.toString());
        assertThat(body(call(athlete, get("/api/v2/videos/exercises"))).get("exercises")).isEmpty();
    }

    @Test
    void ownerRenamesAVideo() throws Exception {
        var technique = uploaded(coach, "technique", "Старое");
        var set = uploaded(athlete, "set", "Пятый подход");

        rename(coach, technique, "Присед: пауза").andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Присед: пауза"));
        rename(coach, technique, "  ").andExpect(status().isBadRequest());
        rename(athlete, technique, "Чужое").andExpect(status().isNotFound());
        rename(athlete, set, "").andExpect(status().isOk()).andExpect(jsonPath("$.title").isEmpty());
        call(athlete, get("/api/v2/videos/" + set)).andExpect(jsonPath("$.title").isEmpty());
        rename(athlete, set, "Тяжёлый").andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Тяжёлый"));
    }

    private org.springframework.test.web.servlet.ResultActions attach(String subject, UUID exercise, UUID video)
            throws Exception {
        return call(subject, put("/api/v2/videos/exercises/" + exercise).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("videoId", video, "exerciseName", "Присед со штангой"))));
    }

    private org.springframework.test.web.servlet.ResultActions rename(String subject, UUID video, String title)
            throws Exception {
        return call(subject, patch("/api/v2/videos/" + video).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("title", title))));
    }

    private UUID uploaded(String subject, String kind, String title) throws Exception {
        var id = UUID.randomUUID();
        var request = new HashMap<String, Object>();
        request.put("id", id);
        request.put("kind", kind);
        request.put("sizeBytes", VIDEO_BYTES);
        request.put("previewSizeBytes", PREVIEW_BYTES);
        request.put("durationMs", DURATION_MS);
        request.put("title", title);
        JsonNode upload = body(postJson(subject, "/api/v2/videos", request).andExpect(status().isOk())).get("upload");
        putFile(upload.get("videoUrl").asString(), "video/mp4", VideoFiles.mp4(VIDEO_BYTES, DURATION_MS));
        putFile(upload.get("previewUrl").asString(), "image/jpeg", VideoFiles.jpeg(PREVIEW_BYTES));
        call(subject, post("/api/v2/videos/" + id + "/complete")).andExpect(status().isOk());
        return id;
    }

    private static void putFile(String url, String contentType, byte[] content) throws Exception {
        var code = HTTP.send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", contentType)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(content)).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
        assertThat(code).isEqualTo(200);
    }
}
