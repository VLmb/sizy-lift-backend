package com.sizylift.backend.video;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.sizylift.backend.CoachWorkspaceApiSupport;
import com.sizylift.backend.video.entity.Video;
import com.sizylift.backend.video.service.VideoProcessing;
import com.sizylift.backend.video.service.VideoStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Загрузка видео с сайта и из PWA (docs/video.md): исходник как есть по подписанной ссылке прямо в настоящий
 * Garage (с CORS для адресов сайта), перекодирование настоящим ffmpeg, превью сервера.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BrowserVideoApiTest extends CoachWorkspaceApiSupport {

    static final String ORIGIN = "https://web.test";
    static final long MAX_VIDEO_BYTES = 26_214_400;
    static final long MAX_PREVIEW_BYTES = 204_800;
    static final HttpClient HTTP = HttpClient.newHttpClient();

    @Autowired
    VideoProcessing processing;

    @Autowired
    VideoStorage storage;

    @TempDir
    Path files;

    String athlete;
    UUID athleteId;

    @BeforeEach
    void account() throws Exception {
        athlete = newSubject();
        athleteId = userId(athlete);
    }

    @Test
    void browserUploadsTheOriginalAndTheServerMakesTheVideoAndPreview() throws Exception {
        // Общий тестовый контекст без адресов сайта: правило CORS ставится тем же вызовом, что и при запуске.
        storage.allowBrowsers(List.of(ORIGIN));
        var source = movie("portrait.mov", "1080x1920", 3);
        var id = UUID.randomUUID();
        var created = body(create(request(id, "set", Files.size(source), "video/quicktime", 3_000))
                .andExpect(status().isOk()));
        var upload = created.get("upload");
        assertThat(upload.get("previewUrl").isNull()).as("превью делает сервер").isTrue();
        assertThat(upload.get("videoContentType").asString()).isEqualTo("video/quicktime");

        var preflight = HTTP.send(HttpRequest.newBuilder(URI.create(upload.get("videoUrl").asString()))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).header("Origin", ORIGIN)
                .header("Access-Control-Request-Method", "PUT")
                .header("Access-Control-Request-Headers", "content-type").build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(preflight.headers().firstValue("Access-Control-Allow-Origin")).as("CORS бакета для сайта")
                .hasValueSatisfying(value -> assertThat(value).isIn(ORIGIN, "*"));

        assertThat(put(upload.get("videoUrl").asString(), "video/quicktime", Files.readAllBytes(source)))
                .isEqualTo(200);
        call(athlete, post("/api/v2/videos/" + id + "/complete")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("processing")).andExpect(jsonPath("$.videoUrl").isEmpty());
        call(athlete, get("/api/v2/videos").param("kind", "set")).andExpect(status().isOk())
                .andExpect(jsonPath("$.videos[0].status").value("processing"));

        processing.processWaiting();

        var ready = body(call(athlete, get("/api/v2/videos/" + id)).andExpect(status().isOk()));
        assertThat(ready.get("status").asString()).isEqualTo("ready");
        assertThat(ready.get("durationMs").asInt()).isBetween(2_900, 3_100);
        assertThat(ready.get("sizeBytes").asLong()).isPositive().isLessThan(MAX_VIDEO_BYTES);
        var video = download(ready.get("videoUrl").asString(), "video.mp4");
        assertThat(ffprobe(video, "stream=width,height")).as("720p, поворот сохранён")
                .contains("width=720", "height=1280");
        assertThat(ffprobe(video, "stream=codec_name")).contains("codec_name=h264", "codec_name=aac");
        var preview = download(ready.get("previewUrl").asString(), "preview.jpg");
        assertThat(Files.size(preview)).isPositive().isLessThanOrEqualTo(MAX_PREVIEW_BYTES);
        assertThat(storage.size(Video.sourceKey(athleteId, id))).as("исходник удалён").isEmpty();
    }

    @Test
    void techniqueFromTheBrowserMayLastTwoMinutes() throws Exception {
        var coach = newSubject();
        link(coach, athlete);
        var source = movie("technique.mp4", "160x120", 95);
        var id = UUID.randomUUID();
        var place = request(id, "technique", Files.size(source), "video/mp4", 95_000);
        place.put("title", "Присед: разбор");
        var upload = body(postJson(coach, "/api/v2/videos", place).andExpect(status().isOk())).get("upload");
        assertThat(put(upload.get("videoUrl").asString(), "video/mp4", Files.readAllBytes(source))).isEqualTo(200);
        call(coach, post("/api/v2/videos/" + id + "/complete")).andExpect(status().isOk());

        processing.processWaiting();

        var ready = body(call(coach, get("/api/v2/videos/" + id)).andExpect(status().isOk()));
        assertThat(ready.get("status").asString()).isEqualTo("ready");
        assertThat(ready.get("durationMs").asInt()).isBetween(94_000, 96_000);
    }

    @Test
    void webmWithoutLengthInTheHeaderIsAccepted() throws Exception {
        var source = movie("recorded.webm", "640x480", 2);
        var id = uploadedSource(source, "video/webm", null);

        processing.processWaiting();

        call(athlete, get("/api/v2/videos/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ready"));
    }

    @Test
    void whatIsNotAVideoOrTooLongIsDroppedAndTakenOffTheComment() throws Exception {
        var notAVideo = files.resolve("photo.mp4");
        Files.write(notAVideo, VideoFiles.random(50_000, 7));
        var junk = uploadedSource(notAVideo, "video/mp4", 3_000);
        var tooLong = uploadedSource(movie("long.mp4", "160x120", 65), "video/mp4", 59_000);
        var workout = UUID.randomUUID();
        var payload = workout();
        push(athlete, item("workout", workout, null, 0, 1, payload));
        var exercise = UUID.fromString(payload.get("blocks").get(0).get("exercises").get(0).get("id").asString());
        push(athlete, item("workout_comment", UUID.randomUUID(), null, 0, 1, json.createObjectNode()
                .put("workoutId", workout.toString()).put("text", "Глянь").put("createdAt", T10.toString())
                .put("updatedAt", T10.toString()).put("workoutExerciseId", exercise.toString())
                .put("setNumber", 1).put("videoId", junk.toString()).put("videoDurationMs", 3_000)));

        processing.processWaiting();

        call(athlete, get("/api/v2/videos/" + junk)).andExpect(status().isNotFound());
        call(athlete, get("/api/v2/videos/" + tooLong)).andExpect(status().isNotFound());
        var comment = body(call(athlete, get("/api/v2/web/workouts/" + workout)).andExpect(status().isOk()))
                .get("comments").get(0).get("payload");
        assertThat(comment.get("videoId").isNull()).isTrue();
        assertThat(comment.get("text").asString()).isEqualTo("Глянь");
    }

    @Test
    void limitsOfTheOriginalAndQuotaReservation() throws Exception {
        create(request(UUID.randomUUID(), "set", 1_000, "image/png", 3_000))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("video_unsupported"));
        create(request(UUID.randomUUID(), "set", 314_572_801L, "video/mp4", 3_000))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("video_too_large"));
        create(request(UUID.randomUUID(), "set", 1_000, "video/mp4", 60_001))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("video_too_long"));

        var id = UUID.randomUUID();
        create(request(id, "set", 200_000_000L, "video/mp4", null)).andExpect(status().isOk());
        // Повтор после обрыва — новые ссылки.
        create(request(id, "set", 200_000_000L, "video/mp4", null)).andExpect(status().isOk()).andExpect(jsonPath("$.upload.videoUrl").isNotEmpty());
        create(request(id, "set", 100_000_000L, "video/mp4", null)).andExpect(status().isConflict());
        var list = body(call(athlete, get("/api/v2/videos").param("kind", "set")).andExpect(status().isOk()));
        assertThat(list.get("quota").get("usedBytes").asLong()).as("в квоте — место под результат, не исходник")
                .isEqualTo(MAX_VIDEO_BYTES + MAX_PREVIEW_BYTES);
    }

    private UUID uploadedSource(Path source, String contentType, Integer durationMs) throws Exception {
        var id = UUID.randomUUID();
        var upload = body(create(request(id, "set", Files.size(source), contentType, durationMs))
                .andExpect(status().isOk())).get("upload");
        assertThat(put(upload.get("videoUrl").asString(), contentType, Files.readAllBytes(source))).isEqualTo(200);
        call(athlete, post("/api/v2/videos/" + id + "/complete")).andExpect(status().isOk());
        return id;
    }

    private ResultActions create(Map<String, Object> request) throws Exception {
        return postJson(athlete, "/api/v2/videos", request);
    }

    private static Map<String, Object> request(UUID id, String kind, long size, String contentType,
                                               Integer durationMs) {
        var request = new HashMap<String, Object>();
        request.put("id", id);
        request.put("kind", kind);
        request.put("source", "browser");
        request.put("sizeBytes", size);
        request.put("contentType", contentType);
        request.put("durationMs", durationMs);
        request.put("exerciseName", "Присед со штангой");
        return request;
    }

    /** Ролик со звуком из генератора ffmpeg; у портретного — поворот в метаданных, как у телефона. */
    private Path movie(String name, String size, int seconds) throws Exception {
        var target = files.resolve(name);
        var command = new ArrayList<>(List.of("ffmpeg", "-v", "error", "-y",
                "-f", "lavfi", "-i", "testsrc=size=" + size + ":rate=30",
                "-f", "lavfi", "-i", "sine=frequency=440", "-t", String.valueOf(seconds)));
        if (name.endsWith(".webm")) {
            command.addAll(List.of("-c:v", "libvpx", "-b:v", "500k", "-c:a", "libopus"));
        } else {
            command.addAll(List.of("-c:v", "libx264", "-preset", "ultrafast", "-c:a", "aac"));
        }
        command.add(target.toString());
        run(command);
        return target;
    }

    private Path download(String url, String name) throws Exception {
        var target = files.resolve(name);
        var response = HTTP.send(HttpRequest.newBuilder(URI.create(url)).build(),
                HttpResponse.BodyHandlers.ofFile(target));
        assertThat(response.statusCode()).isEqualTo(200);
        return target;
    }

    private String ffprobe(Path file, String entries) throws Exception {
        return run(List.of("ffprobe", "-v", "error", "-show_entries", entries, "-of", "default=noprint_wrappers=1",
                file.toString()));
    }

    private static String run(List<String> command) throws IOException, InterruptedException {
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor()).as(output).isZero();
        return output;
    }

    private static int put(String url, String contentType, byte[] content) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", contentType)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(content)).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
