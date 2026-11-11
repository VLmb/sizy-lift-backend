package com.sizylift.backend.web.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.sizylift.backend.inbox.dto.InboxDtos.WorkoutFacts;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CoachRosterRepository {
    private static final String ROSTER = """
            SELECT l.id, l.athlete_user_id, p.display_name, w.id AS workout_id, w.workout_date, w.title,
                   pl.plan_until,
                   greatest(w.completed_at, s.signal_at, l.created_at) AS activity_at,
                   ev.workout_completed, ev.workout_rescheduled, ev.workout_missed, ev.program_ending,
                   ev.invite_accepted, ev.oldest_unseen_at, t.open_tasks, t.overdue_tasks
            FROM sizylift.coaching_link l
            LEFT JOIN sizylift.athlete_profile p ON p.user_id = l.athlete_user_id
            LEFT JOIN LATERAL (
                SELECT id, workout_date, title, completed_at FROM sizylift.workout
                WHERE user_id = l.athlete_user_id ORDER BY workout_date DESC, completed_at DESC LIMIT 1
            ) w ON true
            LEFT JOIN LATERAL (
                SELECT max(changed_at) AS signal_at FROM sizylift.published_aggregate
                WHERE user_id = l.athlete_user_id AND NOT deleted AND author_user_id IS NULL
                  AND type IN ('workout_comment', 'workout_reschedule')
            ) s ON true
            LEFT JOIN LATERAL (
                SELECT count(*) FILTER (WHERE kind = 'workout_completed') AS workout_completed,
                       count(*) FILTER (WHERE kind = 'workout_rescheduled') AS workout_rescheduled,
                       count(*) FILTER (WHERE kind = 'workout_missed') AS workout_missed,
                       count(*) FILTER (WHERE kind = 'program_ending') AS program_ending,
                       count(*) FILTER (WHERE kind = 'invite_accepted') AS invite_accepted,
                       min(occurred_at) AS oldest_unseen_at
                FROM sizylift.coach_event WHERE link_id = l.id AND seen_at IS NULL
            ) ev ON true
            LEFT JOIN LATERAL (
                SELECT max(d.scheduled_date) AS plan_until FROM (
                    SELECT coalesce(r.scheduled_date, pw.workout_date) AS scheduled_date
                    FROM sizylift.planned_workout pw
                    JOIN sizylift.program pr ON pr.user_id = pw.user_id AND pr.id = pw.program_id
                        AND pr.status = 'active'
                    LEFT JOIN sizylift.workout_reschedule r
                        ON r.user_id = pw.user_id AND r.planned_workout_id = pw.id
                    WHERE pw.user_id = l.athlete_user_id
                    UNION ALL
                    SELECT coalesce(r.scheduled_date, s.workout_date)
                    FROM sizylift.standalone_workout s
                    JOIN sizylift.published_aggregate pa ON pa.user_id = s.user_id AND pa.aggregate_id = s.id
                        AND pa.type = 'standalone_workout' AND NOT pa.deleted
                    LEFT JOIN sizylift.workout_reschedule r
                        ON r.user_id = s.user_id AND r.planned_workout_id = s.id
                    WHERE s.user_id = l.athlete_user_id
                ) d
            ) pl ON true
            LEFT JOIN LATERAL (
                SELECT count(*) AS open_tasks, count(*) FILTER (WHERE due_on < ?) AS overdue_tasks
                FROM sizylift.coach_task WHERE link_id = l.id AND done_at IS NULL
            ) t ON true
            WHERE l.coach_user_id = ? AND l.ended_at IS NULL
            ORDER BY ev.oldest_unseen_at ASC NULLS LAST, activity_at DESC, l.id
            """;
    private final JdbcTemplate jdbc;

    public CoachRosterRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Подопечный в списке тренера. {@code unseen} и {@code oldestUnseenAt} — непросмотренные «Входящие»,
     * {@code overdueTasks} — открытые задачи со сроком раньше сегодняшнего дня. {@code workoutFacts} — суть
     * последней тренировки; {@code planUntil} — дата последней плановой тренировки среди активных программ
     * и разовых, с учётом переносов, или {@code null}, если их нет.
     */
    public record AthleteRow(UUID linkId, UUID athleteId, String name, UUID workoutId, LocalDate workoutDate,
                             String workoutTitle, WorkoutFacts workoutFacts, LocalDate planUntil,
                             Instant activityAt, Unseen unseen, Instant oldestUnseenAt, long openTasks,
                             long overdueTasks) {

        public AthleteRow withFacts(WorkoutFacts facts) {
            return new AthleteRow(linkId, athleteId, name, workoutId, workoutDate, workoutTitle, facts, planUntil,
                    activityAt, unseen, oldestUnseenAt, openTasks, overdueTasks);
        }
    }

    public record Unseen(long workoutCompleted, long workoutRescheduled, long workoutMissed, long programEnding,
                         long inviteAccepted) {
    }

    /** Сначала ждущие внимания — кто дольше; затем остальные по свежести активности. */
    public List<AthleteRow> list(UUID coach, LocalDate today) {
        return jdbc.query(ROSTER, CoachRosterRepository::athlete, today, coach);
    }

    private static AthleteRow athlete(ResultSet row, int rowNumber) throws SQLException {
        var oldest = row.getObject("oldest_unseen_at", OffsetDateTime.class);
        return new AthleteRow(row.getObject("id", UUID.class),
                row.getObject("athlete_user_id", UUID.class), row.getString("display_name"),
                row.getObject("workout_id", UUID.class), row.getObject("workout_date", LocalDate.class),
                row.getString("title"), null, row.getObject("plan_until", LocalDate.class),
                row.getObject("activity_at", OffsetDateTime.class).toInstant(), unseen(row),
                oldest == null ? null : oldest.toInstant(), row.getLong("open_tasks"),
                row.getLong("overdue_tasks"));
    }

    private static Unseen unseen(ResultSet row) throws SQLException {
        return new Unseen(row.getLong("workout_completed"), row.getLong("workout_rescheduled"),
                row.getLong("workout_missed"), row.getLong("program_ending"), row.getLong("invite_accepted"));
    }
}
