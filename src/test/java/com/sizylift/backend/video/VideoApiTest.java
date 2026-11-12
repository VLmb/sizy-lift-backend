package com.sizylift.backend.video;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.sizylift.backend.CoachWorkspaceApiSupport;
import com.sizylift.backend.GarageContainer;
import com.sizylift.backend.video.service.VideoMaintenance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Видео подходов и техники (ADR-0012, docs/video.md) на настоящем Garage: подписанные ссылки, доступ, квота,
 * удаление с комментариев и программ, комментарий к упражнению и совместимость прежнего {@code workout_comment}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class VideoApiTest extends CoachWorkspaceApiSupport {

    static final int VIDEO_BYTES = 3 * 1024 * 1024;
    static final int PREVIEW_BYTES = 20_000;
    static final long MAX_VIDEO_BYTES = 26_214_400;
    static final long MAX_PREVIEW_BYTES = 204_800;
    static final int DURATION_MS = 24_000;
    static final int REJECTED = 403;
    static final HttpClient HTTP = HttpClient.newHttpClient();

    @Autowired
    VideoMaintenance maintenance;

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
    void garageTakesSignedUploadsAndServesRangesBeforeTheWholeFileIsRead() throws Exception {
        var id = UUID.randomUUID();
        var video = bytes(VIDEO_BYTES);
        var preview = bytes(PREVIEW_BYTES);
        var created = body(create(athlete, request(id, "set", VIDEO_BYTES, PREVIEW_BYTES)).andExpect(status().isOk()));
        var upload = created.get("upload");
        assertThat(created.get("video").get("status").asString()).isEqualTo("pending");
        assertThat(created.get("video").get("videoUrl").isNull()).as("до загрузки ролик не выдаётся").isTrue();

        call(athlete, post("/api/v2/videos/" + id + "/complete")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("upload_incomplete"));
        assertThat(put(upload.get("videoUrl").asString(), "video/mp4", bytes(VIDEO_BYTES - 1)))
                .as("в подпись входит точный размер").isEqualTo(REJECTED);
        assertThat(put(upload.get("videoUrl").asString(), "image/png", video))
                .as("в подпись входит тип содержимого").isEqualTo(REJECTED);
        assertThat(put(upload.get("videoUrl").asString(), "video/mp4", video)).isEqualTo(200);
        assertThat(put(upload.get("previewUrl").asString(), "image/jpeg", preview)).isEqualTo(200);

        var ready = body(call(athlete, post("/api/v2/videos/" + id + "/complete")).andExpect(status().isOk()));
        assertThat(ready.get("status").asString()).isEqualTo("ready");
        var url = ready.get("videoUrl").asString();
        var range = HTTP.send(HttpRequest.newBuilder(URI.create(url)).header("Range", "bytes=1048576-2097151")
                .build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(range.statusCode()).isEqualTo(206);
        assertThat(range.headers().firstValue("Content-Range")).hasValue("bytes 1048576-2097151/" + VIDEO_BYTES);
        assertThat(range.body()).isEqualTo(java.util.Arrays.copyOfRange(video, 1_048_576, 2_097_152));
        var whole = HTTP.send(HttpRequest.newBuilder(URI.create(ready.get("previewUrl").asString())).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(whole.body()).isEqualTo(preview);

        var anonymous = GarageContainer.endpoint() + "/" + GarageContainer.BUCKET + "/video/" + athleteId + "/" + id
                + ".mp4";
        assertThat(HTTP.send(HttpRequest.newBuilder(URI.create(anonymous)).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode()).as("бакет закрытый").isEqualTo(403);
    }

    @Test
    void setVideoIsSeenByTheOwnerAndTheActiveCoachOnly() throws Exception {
        var id = uploaded(athlete, "set");
        var stranger = newSubject();

        call(athlete, get("/api/v2/videos/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.videoUrl").isNotEmpty()).andExpect(jsonPath("$.usage").exists());
        call(coach, get("/api/v2/videos/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.videoUrl").isNotEmpty()).andExpect(jsonPath("$.usage").isEmpty());
        call(stranger, get("/api/v2/videos/" + id)).andExpect(status().isNotFound());
        call(coach, post("/api/v2/videos/" + id + "/complete")).andExpect(status().isNotFound());
        call(coach, delete("/api/v2/videos/" + id)).andExpect(status().isNotFound());
        call(stranger, delete("/api/v2/videos/" + id)).andExpect(status().isNotFound());
        call(coach, get("/api/v2/videos/" + UUID.randomUUID())).andExpect(status().isNotFound());

        call(athlete, delete("/api/v2/coaching/links/" + linkId)).andExpect(status().isNoContent());
        call(coach, get("/api/v2/videos/" + id)).andExpect(status().isNotFound());
        call(athlete, get("/api/v2/videos/" + id)).andExpect(status().isOk());
    }

    @Test
    void pendingVideoIsVisibleWithoutLinks() throws Exception {
        var id = UUID.randomUUID();
        create(athlete, request(id, "set", VIDEO_BYTES, PREVIEW_BYTES)).andExpect(status().isOk());

        call(coach, get("/api/v2/videos/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("pending")).andExpect(jsonPath("$.videoUrl").isEmpty())
                .andExpect(jsonPath("$.previewUrl").isEmpty());
    }

    @Test
    void techniqueVideoIsSeenByAthletesWhoseProgramHasIt() throws Exception {
        var id = uploaded(coach, "technique");
        var other = newSubject();
        link(coach, other);

        call(athlete, get("/api/v2/videos/" + id)).andExpect(status().isNotFound());
        var program = UUID.randomUUID();
        push(coach, item("program", program, athleteId, 0, 1, programWith(id)));

        call(athlete, get("/api/v2/videos/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.videoUrl").isNotEmpty()).andExpect(jsonPath("$.title").value("Присед: пауза"))
                .andExpect(jsonPath("$.usage").isEmpty());
        call(other, get("/api/v2/videos/" + id)).andExpect(status().isNotFound());
        var read = body(call(athlete, get("/api/v2/web/programs/" + program)).andExpect(status().isOk()));
        assertThat(read.toString()).contains("\"techniqueVideoId\":\"" + id + "\"");

        call(coach, delete("/api/v2/coaching/links/" + linkId)).andExpect(status().isNoContent());
        call(athlete, get("/api/v2/videos/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void techniqueVideoNeedsCoachModeAndATitle() throws Exception {
        create(athlete, request(UUID.randomUUID(), "technique", VIDEO_BYTES, PREVIEW_BYTES))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("coach_required"));
        var untitled = request(UUID.randomUUID(), "technique", VIDEO_BYTES, PREVIEW_BYTES);
        untitled.remove("title");
        create(coach, untitled).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_video"));
    }

    @Test
    void limitsAndQuotaRejectWithAReasonAndDeletingFreesRoom() throws Exception {
        create(athlete, request(UUID.randomUUID(), "set", MAX_VIDEO_BYTES + 1, PREVIEW_BYTES))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("video_too_large"));
        create(athlete, request(UUID.randomUUID(), "set", VIDEO_BYTES, MAX_PREVIEW_BYTES + 1))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("video_too_large"));
        var tooLong = request(UUID.randomUUID(), "set", VIDEO_BYTES, PREVIEW_BYTES);
        tooLong.put("durationMs", 60_001);
        create(athlete, tooLong).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("video_too_long"));
        create(athlete, request(UUID.randomUUID(), "photo", VIDEO_BYTES, PREVIEW_BYTES))
                .andExpect(status().isBadRequest());

        // Квота видео подходов — 300 МБ: одиннадцать роликов по 25 МБ помещаются, двенадцатый — нет.
        var ids = new ArrayList<UUID>();
        for (int i = 0; i < 11; i++) {
            ids.add(UUID.randomUUID());
            create(athlete, request(ids.getLast(), "set", MAX_VIDEO_BYTES, MAX_PREVIEW_BYTES))
                    .andExpect(status().isOk());
        }
        var extra = UUID.randomUUID();
        create(athlete, request(extra, "set", MAX_VIDEO_BYTES, MAX_PREVIEW_BYTES))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("quota_exceeded"));
        create(athlete, request(ids.getFirst(), "set", MAX_VIDEO_BYTES, MAX_PREVIEW_BYTES)).andExpect(status().isOk())
                .andExpect(jsonPath("$.upload.videoUrl").isNotEmpty());
        var list = body(call(athlete, get("/api/v2/videos").param("kind", "set")).andExpect(status().isOk()));
        assertThat(list.get("quota").get("usedBytes").asLong()).isEqualTo(11 * (MAX_VIDEO_BYTES + MAX_PREVIEW_BYTES));
        assertThat(list.get("quota").get("limitBytes").asLong()).isEqualTo(314_572_800L);

        create(coach, request(UUID.randomUUID(), "set", MAX_VIDEO_BYTES, MAX_PREVIEW_BYTES)).andExpect(status().isOk());
        create(coach, request(ids.getFirst(), "set", MAX_VIDEO_BYTES, MAX_PREVIEW_BYTES))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("id_conflict"));

        call(athlete, delete("/api/v2/videos/" + ids.getFirst())).andExpect(status().isOk());
        create(athlete, request(extra, "set", MAX_VIDEO_BYTES, MAX_PREVIEW_BYTES)).andExpect(status().isOk());
    }

    @Test
    void deletingASetVideoTakesItOffCommentsAndRemovesTheFiles() throws Exception {
        var id = uploaded(athlete, "set");
        var workout = UUID.randomUUID();
        var payload = workout();
        push(athlete, item("workout", workout, null, 0, 1, payload));
        var exercise = UUID.fromString(payload.get("blocks").get(0).get("exercises").get(0).get("id").asString());
        var withText = UUID.randomUUID();
        var videoOnly = UUID.randomUUID();
        push(athlete, item("workout_comment", withText, null, 0, 1,
                exerciseComment(workout, "Колено ноет", exercise, 3, id)));
        push(athlete, item("workout_comment", videoOnly, null, 0, 1, exerciseComment(workout, "", exercise, 5, id)
                .put("createdAt", T10.plusSeconds(1).toString())));
        var videoUrl = body(call(athlete, get("/api/v2/videos/" + id))).get("videoUrl").asString();
        var cursor = cursor(coach);

        var deleted = body(call(athlete, delete("/api/v2/videos/" + id)).andExpect(status().isOk()));

        var removedFrom = deleted.get("removedFrom").get("comments");
        assertThat(removedFrom).hasSize(2);
        assertThat(removedFrom.get(0).get("workoutId").asString()).isEqualTo(workout.toString());
        assertThat(removedFrom.get(0).get("workoutDate").asString()).isEqualTo(payload.get("workoutDate").asString());
        assertThat(removedFrom.get(0).get("setNumber").asInt()).isEqualTo(3);
        call(athlete, get("/api/v2/videos/" + id)).andExpect(status().isNotFound());
        var comments = body(call(coach, get("/api/v2/web/workouts/" + workout).param("ownerId", athleteId.toString()))
                .andExpect(status().isOk())).get("comments");
        assertThat(comments).as("комментарий без текста удалён вместе с видео").hasSize(1);
        var kept = comments.get(0);
        assertThat(kept.get("id").asString()).isEqualTo(withText.toString());
        assertThat(kept.get("revision").asLong()).as("новая ревизия").isGreaterThan(1);
        assertThat(kept.get("payload").get("videoId").isNull()).isTrue();
        assertThat(kept.get("payload").get("videoDurationMs").isNull()).isTrue();
        assertThat(kept.get("payload").get("setNumber").asInt()).isEqualTo(3);
        assertThat(kept.get("payload").get("text").asString()).isEqualTo("Колено ноет");
        assertThat(kinds(changes(coach, cursor))).as("лента тренера")
                .containsExactlyInAnyOrder("workout_comment:upsert", "workout_comment:delete");

        maintenance.removeObjects();
        assertThat(HTTP.send(HttpRequest.newBuilder(URI.create(videoUrl)).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode()).as("файл удалён").isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sizylift.video_removal WHERE video_id = ?",
                Integer.class, id)).isZero();
    }

    @Test
    void deletingATechniqueVideoTakesItOffProgramsOfTheCoachAndAthletes() throws Exception {
        var id = uploaded(coach, "technique");
        var template = UUID.randomUUID();
        var assigned = UUID.randomUUID();
        var templatePayload = programWith(id);
        templatePayload.put("name", "Силовой блок");
        var assignedPayload = programWith(id);
        assignedPayload.put("name", "Подготовка к старту");
        push(coach, item("program", template, null, 0, 1, templatePayload));
        push(coach, item("program", assigned, athleteId, 0, 1, assignedPayload));
        var cursor = cursor(athlete);
        var outbox = notifications(athleteId).size();

        var listed = body(call(coach, get("/api/v2/videos").param("kind", "technique")).andExpect(status().isOk()))
                .get("videos").get(0);
        assertThat(names(listed.get("usage").get("programs"))).containsExactly("Подготовка к старту", "Силовой блок");
        assertThat(listed.get("previewUrl").asString()).isNotEmpty();
        assertThat(listed.get("videoUrl").isNull()).as("в списке — только превью").isTrue();

        var deleted = body(call(coach, delete("/api/v2/videos/" + id)).andExpect(status().isOk()));

        assertThat(names(deleted.get("removedFrom").get("programs")))
                .containsExactly("Подготовка к старту", "Силовой блок");
        for (var owner : List.of(Map.entry(coach, template), Map.entry(athlete, assigned))) {
            var program = body(call(owner.getKey(), get("/api/v2/web/programs/" + owner.getValue()))
                    .andExpect(status().isOk()));
            assertThat(program.toString()).doesNotContain(id.toString());
            assertThat(program.get("revision").asLong()).isGreaterThan(1);
        }
        assertThat(kinds(changes(athlete, cursor))).as("лента подопечного").containsExactly("program:upsert");
        assertThat(notifications(athleteId)).as("правка сервера — без push").hasSize(outbox);
    }

    @Test
    void listShowsOwnSetVideosWithWorkoutAndSetAndPages() throws Exception {
        var first = uploaded(athlete, "set");
        var second = uploaded(athlete, "set");
        uploaded(coach, "set");
        var workout = UUID.randomUUID();
        var payload = workout();
        push(athlete, item("workout", workout, null, 0, 1, payload));
        var exercise = UUID.fromString(payload.get("blocks").get(0).get("exercises").get(0).get("id").asString());
        var comment = UUID.randomUUID();
        push(athlete, item("workout_comment", comment, null, 0, 1, exerciseComment(workout, "", exercise, 5, second)));

        var page = body(call(athlete, get("/api/v2/videos").param("kind", "set").param("limit", "1"))
                .andExpect(status().isOk()));
        assertThat(page.get("videos")).hasSize(1);
        var newest = page.get("videos").get(0);
        assertThat(newest.get("id").asString()).isEqualTo(second.toString());
        assertThat(newest.get("exerciseName").asString()).isEqualTo("Присед со штангой");
        assertThat(newest.get("durationMs").asInt()).isEqualTo(DURATION_MS);
        var use = newest.get("usage").get("comments").get(0);
        assertThat(use.get("commentId").asString()).isEqualTo(comment.toString());
        assertThat(use.get("workoutId").asString()).isEqualTo(workout.toString());
        assertThat(use.get("workoutDate").asString()).isEqualTo(payload.get("workoutDate").asString());
        assertThat(use.get("setNumber").asInt()).isEqualTo(5);

        var next = body(call(athlete, get("/api/v2/videos").param("kind", "set").param("limit", "1")
                .param("cursor", page.get("nextCursor").asString())).andExpect(status().isOk()));
        assertThat(next.get("videos").get(0).get("id").asString()).isEqualTo(first.toString());
        assertThat(next.get("nextCursor").isNull()).isTrue();
        call(athlete, get("/api/v2/videos").param("kind", "technique")).andExpect(status().isOk())
                .andExpect(jsonPath("$.videos").isEmpty());
    }

    @Test
    void exerciseCommentKeepsItsReferencesNotifiesAndOldCommentsWorkAsBefore() throws Exception {
        var workout = UUID.randomUUID();
        var payload = workout();
        push(athlete, item("workout", workout, null, 0, 1, payload));
        var exercise = UUID.fromString(payload.get("blocks").get(0).get("exercises").get(0).get("id").asString());
        var video = UUID.randomUUID();

        // Прежний клиент: только workoutId, text и время.
        var old = UUID.randomUUID();
        push(athlete, item("workout_comment", old, null, 0, 1, comment(workout, "Общий комментарий")));
        // Комментарий к упражнению: видео ещё не загружено — запись всё равно принимается.
        var scoped = UUID.randomUUID();
        push(athlete, item("workout_comment", scoped, null, 0, 1,
                exerciseComment(workout, "Колено ноет", exercise, 3, video)));
        var reply = UUID.randomUUID();
        var answer = comment(workout, "Сними вес");
        answer.put("workoutExerciseId", exercise.toString());
        push(coach, item("workout_comment", reply, athleteId, 0, 1, answer));

        var comments = body(call(coach, get("/api/v2/web/workouts/" + workout).param("ownerId", athleteId.toString()))
                .andExpect(status().isOk())).get("comments");
        var byId = new HashMap<String, JsonNode>();
        comments.forEach(c -> byId.put(c.get("id").asString(), c.get("payload")));
        assertThat(byId.get(old.toString()).get("workoutExerciseId").isNull()).isTrue();
        assertThat(byId.get(old.toString()).get("text").asString()).isEqualTo("Общий комментарий");
        var stored = byId.get(scoped.toString());
        assertThat(stored.get("workoutExerciseId").asString()).isEqualTo(exercise.toString());
        assertThat(stored.get("setNumber").asInt()).isEqualTo(3);
        assertThat(stored.get("videoId").asString()).isEqualTo(video.toString());
        assertThat(stored.get("videoDurationMs").asInt()).isEqualTo(DURATION_MS);
        assertThat(byId.get(reply.toString()).get("workoutExerciseId").asString()).isEqualTo(exercise.toString());

        assertThat(notifications(coachId)).as("push «Новый комментарий» тренеру")
                .filteredOn("comment_added"::equals).hasSize(2);
        assertThat(notifications(athleteId)).as("push «Новый комментарий» подопечному")
                .filteredOn("comment_added"::equals).hasSize(1);
        call(coach, get("/api/v2/videos/" + video)).andExpect(status().isNotFound());

        assertThat(rejected(athlete, comment(workout, " "))).as("пустой текст без видео").isEqualTo("invalid_payload");
        var setWithoutExercise = comment(workout, "Третий подход");
        setWithoutExercise.put("setNumber", 3);
        assertThat(rejected(athlete, setWithoutExercise)).isEqualTo("invalid_payload");
        var videoWithoutExercise = comment(workout, "Видео");
        videoWithoutExercise.put("videoId", video.toString());
        assertThat(rejected(athlete, videoWithoutExercise)).isEqualTo("invalid_payload");
    }

    @Test
    void staleUnfinishedUploadsAreDropped() throws Exception {
        var stale = UUID.randomUUID();
        var fresh = UUID.randomUUID();
        var ready = uploaded(athlete, "set");
        create(athlete, request(stale, "set", VIDEO_BYTES, PREVIEW_BYTES)).andExpect(status().isOk());
        create(athlete, request(fresh, "set", VIDEO_BYTES, PREVIEW_BYTES)).andExpect(status().isOk());
        jdbc.update("UPDATE sizylift.video SET created_at = now() - interval '25 hours' WHERE id IN (?, ?)", stale,
                ready);

        maintenance.dropStalePending();

        call(athlete, get("/api/v2/videos/" + stale)).andExpect(status().isNotFound());
        call(athlete, get("/api/v2/videos/" + fresh)).andExpect(status().isOk());
        call(athlete, get("/api/v2/videos/" + ready)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sizylift.video_removal WHERE video_id = ?",
                Integer.class, stale)).isEqualTo(1);
    }

    private UUID uploaded(String subject, String kind) throws Exception {
        var id = UUID.randomUUID();
        var upload = body(create(subject, request(id, kind, VIDEO_BYTES, PREVIEW_BYTES)).andExpect(status().isOk()))
                .get("upload");
        assertThat(put(upload.get("videoUrl").asString(), upload.get("videoContentType").asString(),
                bytes(VIDEO_BYTES))).isEqualTo(200);
        assertThat(put(upload.get("previewUrl").asString(), upload.get("previewContentType").asString(),
                bytes(PREVIEW_BYTES))).isEqualTo(200);
        call(subject, post("/api/v2/videos/" + id + "/complete")).andExpect(status().isOk());
        return id;
    }

    private ResultActions create(String subject, Map<String, Object> request) throws Exception {
        return postJson(subject, "/api/v2/videos", request);
    }

    private static Map<String, Object> request(UUID id, String kind, long size, long previewSize) {
        var request = new HashMap<String, Object>();
        request.put("id", id);
        request.put("kind", kind);
        request.put("sizeBytes", size);
        request.put("previewSizeBytes", previewSize);
        request.put("durationMs", DURATION_MS);
        request.put("exerciseId", "14000000-0000-4000-8000-000000000001");
        request.put("exerciseName", "Присед со штангой");
        if (kind.equals("technique")) {
            request.put("title", "Присед: пауза");
        }
        return request;
    }

    /** Отклонённую загрузку хранилище может оборвать, не дочитав тело: обрыв — тоже отказ. */
    private static int put(String url, String contentType, byte[] content) throws Exception {
        try {
            return send(url, contentType, content);
        } catch (java.io.IOException e) {
            return REJECTED;
        }
    }

    private static int send(String url, String contentType, byte[] content) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", contentType)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(content)).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static byte[] bytes(int size) {
        var content = new byte[size];
        new java.util.Random(size).nextBytes(content);
        return content;
    }

    private ObjectNode comment(UUID workout, String text) {
        return json.createObjectNode().put("workoutId", workout.toString()).put("text", text)
                .put("createdAt", T10.toString()).put("updatedAt", T10.toString());
    }

    private ObjectNode exerciseComment(UUID workout, String text, UUID exercise, int setNumber, UUID video) {
        return comment(workout, text).put("workoutExerciseId", exercise.toString()).put("setNumber", setNumber)
                .put("videoId", video.toString()).put("videoDurationMs", DURATION_MS);
    }

    /** Программа из фикстуры с видео техники у первого упражнения. */
    private ObjectNode programWith(UUID video) throws Exception {
        var program = withFreshIds("program");
        var exercise = (ObjectNode) program.get("cycles").get(0).get("weeks").get(0).get("workouts").get(0)
                .get("blocks").get(0).get("exercises").get(0);
        exercise.put("techniqueVideoId", video.toString());
        return program;
    }

    private String rejected(String subject, ObjectNode payload) throws Exception {
        var item = item("workout_comment", UUID.randomUUID(), null, 0, 1, payload);
        var result = body(call(subject, post("/api/v2/sync/push").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("items", List.of(item))))).andExpect(status().isOk()))
                .get("results").get(0);
        assertThat(result.get("status").asString()).isEqualTo("rejected");
        return result.get("error").asString();
    }

    private List<String> notifications(UUID user) {
        return jdbc.queryForList("SELECT kind FROM sizylift.notification_outbox WHERE user_id = ?", String.class,
                user);
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

    private static List<String> kinds(List<JsonNode> changes) {
        return changes.stream().map(c -> c.get("type").asString() + ":" + c.get("kind").asString()).toList();
    }

    private static List<String> names(JsonNode programs) {
        var names = new ArrayList<String>();
        programs.forEach(program -> names.add(program.get("name").asString()));
        return names;
    }
}
