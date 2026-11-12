package com.sizylift.backend.video.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.sizylift.backend.video.entity.Video;
import com.sizylift.backend.video.entity.VideoKind;
import com.sizylift.backend.video.entity.VideoStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Записи о роликах, их использование в комментариях и программах, очередь удаления файлов. */
@Repository
public class VideoRepository {

    private static final String COLUMNS = """
            id, owner_user_id, kind, status, size_bytes, preview_size_bytes, duration_ms, title, exercise_id,
            exercise_name, created_at, uploaded_at
            """;
    private static final String LOCK_OWNER = "SELECT id FROM sizylift.user_account WHERE id = ? FOR UPDATE";
    private static final String COACH_ENABLED = "SELECT coach_enabled FROM sizylift.user_account WHERE id = ?";
    private static final String FIND = "SELECT " + COLUMNS + " FROM sizylift.video WHERE id = ?";
    private static final String FIND_FOR_UPDATE = FIND + " FOR UPDATE";
    private static final String INSERT = """
            INSERT INTO sizylift.video (id, owner_user_id, kind, status, size_bytes, preview_size_bytes, duration_ms,
                title, exercise_id, exercise_name, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String MARK_READY =
            "UPDATE sizylift.video SET status = 'ready', uploaded_at = ? WHERE id = ? AND status = 'pending'";
    private static final String DELETE = "DELETE FROM sizylift.video WHERE id = ?";
    private static final String USED_BYTES = """
            SELECT coalesce(sum(size_bytes + preview_size_bytes), 0) FROM sizylift.video
            WHERE owner_user_id = ? AND kind = ?
            """;
    private static final String TOTAL_BYTES =
            "SELECT coalesce(sum(size_bytes + preview_size_bytes), 0) FROM sizylift.video";
    private static final String LIST = "SELECT " + COLUMNS + """
            FROM sizylift.video
            WHERE owner_user_id = ? AND kind = ?
              AND (?::timestamptz IS NULL OR (created_at, id) < (?::timestamptz, ?::uuid))
            ORDER BY created_at DESC, id DESC
            LIMIT ?
            """;
    private static final String STALE_PENDING =
            "SELECT id FROM sizylift.video WHERE status = 'pending' AND created_at < ? ORDER BY id";
    private static final String OWNED_IDS = "SELECT id FROM sizylift.video WHERE owner_user_id = ? ORDER BY id";

    private static final String COMMENT_USES = """
            SELECT c.video_id, c.user_id, c.id, c.workout_id, c.workout_exercise_id, c.set_number, c.text,
                   w.workout_date
            FROM sizylift.workout_comment c
            LEFT JOIN sizylift.workout w ON w.user_id = c.user_id AND w.id = c.workout_id
            WHERE c.video_id = ANY (?)
            ORDER BY c.created_at, c.id
            """;
    private static final String PROGRAM_USES = """
            SELECT DISTINCT e.technique_video_id, e.user_id, e.program_id, p.name
            FROM sizylift.planned_exercise e
            JOIN sizylift.program p ON p.user_id = e.user_id AND p.id = e.program_id
            WHERE e.technique_video_id = ANY (?)
            ORDER BY p.name, e.program_id
            """;
    private static final String ACTIVE_ATHLETES =
            "SELECT athlete_user_id FROM sizylift.coaching_link WHERE coach_user_id = ? AND ended_at IS NULL";
    private static final String ATTACHED_TO_PROGRAM_OF = """
            SELECT EXISTS (SELECT 1 FROM sizylift.planned_exercise WHERE user_id = ? AND technique_video_id = ?)
            """;
    private static final String DETACH_FROM_COMMENT = """
            UPDATE sizylift.workout_comment SET video_id = NULL, video_duration_ms = NULL
            WHERE user_id = ? AND id = ? AND video_id = ?
            """;
    private static final String DETACH_FROM_PROGRAM = """
            UPDATE sizylift.planned_exercise SET technique_video_id = NULL
            WHERE user_id = ? AND program_id = ? AND technique_video_id = ?
            """;

    private static final String QUEUE_REMOVAL = """
            INSERT INTO sizylift.video_removal (video_id, owner_user_id, queued_at) VALUES (?, ?, ?)
            ON CONFLICT (video_id) DO NOTHING
            """;
    private static final String NEXT_REMOVALS = """
            SELECT video_id, owner_user_id FROM sizylift.video_removal
            ORDER BY queued_at, video_id LIMIT ? FOR UPDATE SKIP LOCKED
            """;
    private static final String REMOVAL_DONE = "DELETE FROM sizylift.video_removal WHERE video_id = ?";

    private final JdbcTemplate jdbc;

    public VideoRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Квота считается под исключительной блокировкой аккаунта: две загрузки не обходят её вместе. */
    public void lockOwner(UUID ownerId) {
        jdbc.query(LOCK_OWNER, (row, n) -> null, ownerId);
    }

    public boolean coachEnabled(UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(COACH_ENABLED, Boolean.class, userId));
    }

    public Optional<Video> find(UUID id) {
        return jdbc.query(FIND, VideoRepository::video, id).stream().findFirst();
    }

    public Optional<Video> lock(UUID id) {
        return jdbc.query(FIND_FOR_UPDATE, VideoRepository::video, id).stream().findFirst();
    }

    public void insert(Video video) {
        jdbc.update(INSERT, video.id(), video.ownerId(), video.kind().code(), video.status().code(),
                video.sizeBytes(), video.previewSizeBytes(), video.durationMs(), video.title(), video.exerciseId(),
                video.exerciseName(), Timestamp.from(video.createdAt()));
    }

    public void markReady(UUID id, Instant uploadedAt) {
        jdbc.update(MARK_READY, Timestamp.from(uploadedAt), id);
    }

    public void delete(UUID id) {
        jdbc.update(DELETE, id);
    }

    public long usedBytes(UUID ownerId, VideoKind kind) {
        return jdbc.queryForObject(USED_BYTES, Long.class, ownerId, kind.code());
    }

    public long totalBytes() {
        return jdbc.queryForObject(TOTAL_BYTES, Long.class);
    }

    /** Свои ролики, новые сначала; {@code before} — последний ролик предыдущей страницы. */
    public List<Video> list(UUID ownerId, VideoKind kind, Instant beforeCreatedAt, UUID beforeId, int limit) {
        var before = beforeCreatedAt == null ? null : Timestamp.from(beforeCreatedAt);
        return jdbc.query(LIST, VideoRepository::video, ownerId, kind.code(), before, before, beforeId, limit);
    }

    public List<UUID> stalePending(Instant createdBefore) {
        return jdbc.query(STALE_PENDING, (row, n) -> row.getObject("id", UUID.class), Timestamp.from(createdBefore));
    }

    public List<UUID> ownedIds(UUID ownerId) {
        return jdbc.query(OWNED_IDS, (row, n) -> row.getObject("id", UUID.class), ownerId);
    }

    public List<CommentUse> commentUses(Collection<UUID> videoIds) {
        return jdbc.query(COMMENT_USES, VideoRepository::commentUse, (Object) videoIds.toArray(UUID[]::new));
    }

    public List<ProgramUse> programUses(Collection<UUID> videoIds) {
        return jdbc.query(PROGRAM_USES, VideoRepository::programUse, (Object) videoIds.toArray(UUID[]::new));
    }

    public List<UUID> activeAthletesOf(UUID coachId) {
        return jdbc.query(ACTIVE_ATHLETES, (row, n) -> row.getObject("athlete_user_id", UUID.class), coachId);
    }

    public boolean attachedToProgramOf(UUID userId, UUID videoId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(ATTACHED_TO_PROGRAM_OF, Boolean.class, userId, videoId));
    }

    public void detachFromComment(UUID ownerId, UUID commentId, UUID videoId) {
        jdbc.update(DETACH_FROM_COMMENT, ownerId, commentId, videoId);
    }

    public void detachFromProgram(UUID ownerId, UUID programId, UUID videoId) {
        jdbc.update(DETACH_FROM_PROGRAM, ownerId, programId, videoId);
    }

    public void queueRemoval(UUID videoId, UUID ownerId, Instant now) {
        jdbc.update(QUEUE_REMOVAL, videoId, ownerId, Timestamp.from(now));
    }

    public List<Removal> nextRemovals(int limit) {
        return jdbc.query(NEXT_REMOVALS, (row, n) -> new Removal(row.getObject("video_id", UUID.class),
                row.getObject("owner_user_id", UUID.class)), limit);
    }

    public void removalDone(UUID videoId) {
        jdbc.update(REMOVAL_DONE, videoId);
    }

    private static Video video(ResultSet row, int rowNumber) throws SQLException {
        var uploadedAt = row.getTimestamp("uploaded_at");
        return new Video(row.getObject("id", UUID.class), row.getObject("owner_user_id", UUID.class),
                VideoKind.parse(row.getString("kind")).orElseThrow(), VideoStatus.of(row.getString("status")),
                row.getLong("size_bytes"), row.getLong("preview_size_bytes"), row.getInt("duration_ms"),
                row.getString("title"), row.getObject("exercise_id", UUID.class), row.getString("exercise_name"),
                row.getTimestamp("created_at").toInstant(), uploadedAt == null ? null : uploadedAt.toInstant());
    }

    private static CommentUse commentUse(ResultSet row, int rowNumber) throws SQLException {
        return new CommentUse(row.getObject("video_id", UUID.class), row.getObject("user_id", UUID.class),
                row.getObject("id", UUID.class), row.getObject("workout_id", UUID.class),
                row.getObject("workout_exercise_id", UUID.class), row.getObject("set_number", Integer.class),
                row.getString("text").isBlank(), row.getObject("workout_date", LocalDate.class));
    }

    private static ProgramUse programUse(ResultSet row, int rowNumber) throws SQLException {
        return new ProgramUse(row.getObject("technique_video_id", UUID.class),
                row.getObject("user_id", UUID.class), row.getObject("program_id", UUID.class),
                row.getString("name"));
    }

    /** Комментарий с роликом; {@code workoutDate} пуст, пока тренировка не опубликована. */
    public record CommentUse(UUID videoId, UUID ownerId, UUID commentId, UUID workoutId, UUID workoutExerciseId,
                             Integer setNumber, boolean textBlank, LocalDate workoutDate) {
    }

    /** Программа, в упражнениях которой прикреплён ролик. */
    public record ProgramUse(UUID videoId, UUID ownerId, UUID programId, String name) {
    }

    public record Removal(UUID videoId, UUID ownerId) {
    }
}
