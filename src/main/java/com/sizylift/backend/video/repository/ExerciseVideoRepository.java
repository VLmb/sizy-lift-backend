package com.sizylift.backend.video.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.sizylift.backend.video.entity.VideoKind;
import com.sizylift.backend.video.entity.VideoStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Видео техники у упражнения: одно на упражнение у тренера (docs/video.md, «Видео упражнения»). */
@Repository
public class ExerciseVideoRepository {

    private static final String UPSERT = """
            INSERT INTO sizylift.exercise_video (coach_user_id, exercise_id, video_id, exercise_name, coach_owned,
                attached_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (coach_user_id, exercise_id) DO UPDATE
            SET video_id = excluded.video_id, exercise_name = excluded.exercise_name,
                coach_owned = excluded.coach_owned, attached_at = excluded.attached_at
            """;
    private static final String DELETE =
            "DELETE FROM sizylift.exercise_video WHERE coach_user_id = ? AND exercise_id = ?";
    private static final String LINKS_OF = """
            SELECT e.exercise_id, e.exercise_name, e.coach_owned, e.coach_user_id, v.id, v.kind, v.status, v.title,
                   v.duration_ms
            FROM sizylift.exercise_video e
            JOIN sizylift.video v ON v.id = e.video_id
            WHERE e.coach_user_id = ?
            ORDER BY e.exercise_name, e.exercise_id
            """;
    private static final String USES = """
            SELECT video_id, exercise_id, exercise_name FROM sizylift.exercise_video
            WHERE video_id = ANY (?)
            ORDER BY exercise_name, exercise_id
            """;
    private static final String LINKED = """
            SELECT EXISTS (SELECT 1 FROM sizylift.exercise_video WHERE coach_user_id = ? AND video_id = ?)
            """;

    private final JdbcTemplate jdbc;

    public ExerciseVideoRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Ставит или заменяет видео упражнения тренера. */
    public void attach(UUID coachId, UUID exerciseId, UUID videoId, String exerciseName, boolean coachOwned,
                       Instant attachedAt) {
        jdbc.update(UPSERT, coachId, exerciseId, videoId, exerciseName, coachOwned, Timestamp.from(attachedAt));
    }

    /** {@code false} — у упражнения не было видео. */
    public boolean detach(UUID coachId, UUID exerciseId) {
        return jdbc.update(DELETE, coachId, exerciseId) > 0;
    }

    public List<Link> linksOf(UUID coachId) {
        return jdbc.query(LINKS_OF, ExerciseVideoRepository::link, coachId);
    }

    public List<Use> uses(Collection<UUID> videoIds) {
        return jdbc.query(USES, (row, n) -> new Use(row.getObject("video_id", UUID.class),
                row.getObject("exercise_id", UUID.class), row.getString("exercise_name")),
                (Object) videoIds.toArray(UUID[]::new));
    }

    /** Ролик тренера прикреплён к какому-нибудь его упражнению. */
    public boolean linked(UUID coachId, UUID videoId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(LINKED, Boolean.class, coachId, videoId));
    }

    private static Link link(ResultSet row, int rowNumber) throws SQLException {
        return new Link(row.getObject("exercise_id", UUID.class), row.getString("exercise_name"),
                row.getBoolean("coach_owned"), row.getObject("coach_user_id", UUID.class),
                row.getObject("id", UUID.class), VideoKind.parse(row.getString("kind")).orElseThrow(),
                VideoStatus.of(row.getString("status")), row.getString("title"), row.getInt("duration_ms"));
    }

    /** Упражнение тренера и его видео. {@code coachOwned} — упражнение из библиотеки тренера, не из каталога. */
    public record Link(UUID exerciseId, String exerciseName, boolean coachOwned, UUID coachId, UUID videoId,
                       VideoKind kind, VideoStatus status, String title, int durationMs) {
    }

    /** Где ролик прикреплён как видео упражнения. */
    public record Use(UUID videoId, UUID exerciseId, String exerciseName) {
    }
}
