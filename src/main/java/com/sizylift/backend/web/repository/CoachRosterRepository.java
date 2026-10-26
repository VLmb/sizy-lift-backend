package com.sizylift.backend.web.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CoachRosterRepository {
    private static final String ROSTER = """
            SELECT l.id, l.athlete_user_id, p.display_name, w.workout_date, w.title,
                   greatest(w.completed_at, s.signal_at, l.created_at) AS activity_at,
                   s.signal_at > coalesce(l.coach_seen_at, l.created_at) AS has_new
            FROM sizylift.coaching_link l
            LEFT JOIN sizylift.athlete_profile p ON p.user_id = l.athlete_user_id
            LEFT JOIN LATERAL (
                SELECT workout_date, title, completed_at FROM sizylift.workout
                WHERE user_id = l.athlete_user_id ORDER BY workout_date DESC, completed_at DESC LIMIT 1
            ) w ON true
            LEFT JOIN LATERAL (
                SELECT max(changed_at) AS signal_at FROM sizylift.published_aggregate
                WHERE user_id = l.athlete_user_id AND NOT deleted AND author_user_id IS NULL
                  AND type IN ('workout_comment', 'workout_reschedule')
            ) s ON true
            WHERE l.coach_user_id = ? AND l.ended_at IS NULL
            ORDER BY activity_at DESC, l.id
            """;
    private static final String SEEN = """
            UPDATE sizylift.coaching_link SET coach_seen_at = now()
            WHERE id = ? AND coach_user_id = ? AND ended_at IS NULL
            """;
    private final JdbcTemplate jdbc;

    public CoachRosterRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record AthleteRow(UUID linkId, UUID athleteId, String name, LocalDate workoutDate,
                             String workoutTitle, Instant activityAt, boolean hasNew) {
    }

    public List<AthleteRow> list(UUID coach) {
        return jdbc.query(ROSTER, (row, n) -> new AthleteRow(row.getObject("id", UUID.class),
                row.getObject("athlete_user_id", UUID.class), row.getString("display_name"),
                row.getObject("workout_date", LocalDate.class), row.getString("title"),
                row.getObject("activity_at", OffsetDateTime.class).toInstant(), row.getBoolean("has_new")), coach);
    }

    public boolean seen(UUID coach, UUID linkId) {
        return jdbc.update(SEEN, linkId, coach) > 0;
    }
}
