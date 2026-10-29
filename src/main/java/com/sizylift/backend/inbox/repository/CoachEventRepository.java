package com.sizylift.backend.inbox.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.sizylift.backend.inbox.entity.CoachEventKind;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * События «Входящих» (changeset 020). Запись идёт по активной связи подопечного; чтение и отметка — только
 * по активным связям тренера.
 */
@Repository
public class CoachEventRepository {

    private static final String KEY_SEPARATOR = ":";

    private static final String INSERT_INVITE_ACCEPTED = """
            INSERT INTO sizylift.coach_event (link_id, kind, occurred_at, dedup_key)
            VALUES (?, 'invite_accepted', ?, 'invite_accepted')
            ON CONFLICT (link_id, dedup_key) DO NOTHING
            """;
    /** Повторная публикация той же тренировки нового события не даёт. */
    private static final String INSERT_WORKOUT_COMPLETED = """
            INSERT INTO sizylift.coach_event (link_id, kind, subject_id, subject_date, occurred_at, dedup_key)
            SELECT l.id, 'workout_completed', w.id, w.workout_date, ?, 'workout_completed' || ? || w.id
            FROM sizylift.coaching_link l
            JOIN sizylift.workout w ON w.user_id = l.athlete_user_id AND w.id = ?
            WHERE l.athlete_user_id = ? AND l.ended_at IS NULL
            ON CONFLICT (link_id, dedup_key) DO NOTHING
            """;
    /** Новый перенос той же тренировки поднимает событие наверх и снова делает его непросмотренным. */
    private static final String UPSERT_WORKOUT_RESCHEDULED = """
            INSERT INTO sizylift.coach_event (link_id, kind, subject_id, subject_date, occurred_at, dedup_key)
            SELECT l.id, 'workout_rescheduled', r.planned_workout_id, r.scheduled_date, ?,
                   'workout_rescheduled' || ? || r.planned_workout_id
            FROM sizylift.coaching_link l
            JOIN sizylift.workout_reschedule r ON r.user_id = l.athlete_user_id AND r.planned_workout_id = ?
            WHERE l.athlete_user_id = ? AND l.ended_at IS NULL
            ON CONFLICT (link_id, dedup_key) DO UPDATE
                SET occurred_at = excluded.occurred_at, subject_date = excluded.subject_date, seen_at = NULL
            """;
    private static final String DELETE_BY_SUBJECT = """
            DELETE FROM sizylift.coach_event e
            USING sizylift.coaching_link l
            WHERE l.id = e.link_id AND l.athlete_user_id = ? AND e.kind = ? AND e.subject_id = ?
            """;
    /** Тренировку по плану всё-таки выполнили — «пропущена» больше не верно. */
    private static final String DELETE_MISSED_OF_WORKOUT = """
            DELETE FROM sizylift.coach_event e
            USING sizylift.coaching_link l, sizylift.workout w
            WHERE l.id = e.link_id AND l.athlete_user_id = ? AND e.kind = 'workout_missed'
              AND w.user_id = l.athlete_user_id AND w.id = ? AND e.subject_id = w.planned_workout_id
            """;
    /**
     * Плановые тренировки активных программ с датой (с учётом переноса) в {@code [from, before)}, не раньше
     * создания связи и без завершённой тренировки по ним. Ключ включает дату: перенесённая и снова пропущенная
     * тренировка даёт новое событие.
     */
    private static final String INSERT_MISSED = """
            INSERT INTO sizylift.coach_event (link_id, kind, subject_id, subject_date, occurred_at, dedup_key)
            SELECT l.id, 'workout_missed', pw.id, d.effective_date, ?,
                   'workout_missed' || ? || pw.id || ? || d.effective_date
            FROM sizylift.coaching_link l
            JOIN sizylift.program p ON p.user_id = l.athlete_user_id AND p.status = 'active'
            JOIN sizylift.planned_workout pw ON pw.user_id = p.user_id AND pw.program_id = p.id
            LEFT JOIN sizylift.workout_reschedule r ON r.user_id = pw.user_id AND r.planned_workout_id = pw.id
            CROSS JOIN LATERAL (SELECT coalesce(r.scheduled_date, pw.workout_date) AS effective_date) d
            WHERE l.ended_at IS NULL
              AND d.effective_date >= ? AND d.effective_date < ?
              AND d.effective_date >= (l.created_at AT TIME ZONE ?)::date
              AND NOT EXISTS (SELECT 1 FROM sizylift.workout w
                              WHERE w.user_id = pw.user_id AND w.planned_workout_id = pw.id)
            ON CONFLICT (link_id, dedup_key) DO NOTHING
            """;
    /** Конец программы — последний день её последней недели. Продлённая программа даст новое событие. */
    private static final String INSERT_PROGRAM_ENDING = """
            INSERT INTO sizylift.coach_event (link_id, kind, subject_id, subject_date, occurred_at, dedup_key)
            SELECT l.id, 'program_ending', p.id, e.end_date, ?, 'program_ending' || ? || p.id || ? || e.end_date
            FROM sizylift.coaching_link l
            JOIN sizylift.program p ON p.user_id = l.athlete_user_id AND p.status = 'active'
            CROSS JOIN LATERAL (SELECT max(w.end_date) AS end_date FROM sizylift.training_week w
                                WHERE w.user_id = p.user_id AND w.program_id = p.id) e
            WHERE l.ended_at IS NULL AND e.end_date BETWEEN ? AND ?
            ON CONFLICT (link_id, dedup_key) DO NOTHING
            """;
    private static final String TRY_LOCK_DAILY = "SELECT pg_try_advisory_xact_lock(hashtextextended(?, 0))";

    private static final String FEED = """
            SELECT e.id, l.athlete_user_id, ap.display_name, e.kind, e.subject_id, e.subject_date,
                   e.occurred_at, e.seen_at,
                   CASE e.kind
                       WHEN 'workout_completed' THEN (SELECT w.title FROM sizylift.workout w
                                                      WHERE w.user_id = l.athlete_user_id AND w.id = e.subject_id)
                       WHEN 'program_ending' THEN (SELECT p.name FROM sizylift.program p
                                                   WHERE p.user_id = l.athlete_user_id AND p.id = e.subject_id)
                       WHEN 'invite_accepted' THEN NULL
                       ELSE (SELECT pw.title FROM sizylift.planned_workout pw
                             WHERE pw.user_id = l.athlete_user_id AND pw.id = e.subject_id)
                   END AS title
            FROM sizylift.coach_event e
            JOIN sizylift.coaching_link l ON l.id = e.link_id
            LEFT JOIN sizylift.athlete_profile ap ON ap.user_id = l.athlete_user_id
            WHERE l.coach_user_id = ? AND l.ended_at IS NULL
              AND (?::uuid IS NULL OR l.athlete_user_id = ?::uuid)
              AND (NOT ? OR e.seen_at IS NULL)
              AND (?::timestamptz IS NULL OR e.occurred_at < ?::timestamptz
                   OR ?::uuid IS NOT NULL AND e.occurred_at = ?::timestamptz AND e.id < ?::uuid)
            ORDER BY e.occurred_at DESC, e.id DESC
            LIMIT ?
            """;
    private static final String UNSEEN_COUNT = """
            SELECT count(*) FROM sizylift.coach_event e
            JOIN sizylift.coaching_link l ON l.id = e.link_id
            WHERE l.coach_user_id = ? AND l.ended_at IS NULL AND e.seen_at IS NULL
              AND (?::uuid IS NULL OR l.athlete_user_id = ?::uuid)
            """;
    /** Условия складываются через «и»; не заданное условие ничего не ограничивает. */
    private static final String MARK_SEEN = """
            UPDATE sizylift.coach_event e SET seen_at = ?
            FROM sizylift.coaching_link l
            WHERE l.id = e.link_id AND l.coach_user_id = ? AND l.ended_at IS NULL AND e.seen_at IS NULL
              AND (?::uuid[] IS NULL OR e.id = ANY (?::uuid[]))
              AND (?::uuid IS NULL OR l.athlete_user_id = ?::uuid)
              AND (?::uuid IS NULL OR e.subject_id = ?::uuid)
            """;

    private final JdbcTemplate jdbc;

    public CoachEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record EventRow(UUID id, UUID athleteId, String athleteName, CoachEventKind kind, UUID subjectId,
                           LocalDate subjectDate, String title, Instant occurredAt, Instant seenAt) {
    }

    /** Фильтры ленты; {@code null} — без ограничения. {@code beforeId} разводит события с одинаковым временем. */
    public record FeedQuery(UUID coachId, UUID athleteId, boolean unseenOnly, Instant before, UUID beforeId,
                            int limit) {
    }

    public void inviteAccepted(UUID linkId, Instant occurredAt) {
        jdbc.update(INSERT_INVITE_ACCEPTED, linkId, Timestamp.from(occurredAt));
    }

    public void workoutCompleted(UUID athleteId, UUID workoutId, Instant occurredAt) {
        jdbc.update(INSERT_WORKOUT_COMPLETED, Timestamp.from(occurredAt), KEY_SEPARATOR, workoutId, athleteId);
        jdbc.update(DELETE_MISSED_OF_WORKOUT, athleteId, workoutId);
    }

    public void workoutRescheduled(UUID athleteId, UUID plannedWorkoutId, Instant occurredAt) {
        jdbc.update(UPSERT_WORKOUT_RESCHEDULED, Timestamp.from(occurredAt), KEY_SEPARATOR, plannedWorkoutId,
                athleteId);
    }

    /** Удаляет события подопечного этого типа по объекту, просмотренные тоже. */
    public void deleteBySubject(UUID athleteId, CoachEventKind kind, UUID subjectId) {
        jdbc.update(DELETE_BY_SUBJECT, athleteId, kind.code(), subjectId);
    }

    public int insertMissed(LocalDate from, LocalDate before, String zoneId, Instant occurredAt) {
        return jdbc.update(INSERT_MISSED, Timestamp.from(occurredAt), KEY_SEPARATOR, KEY_SEPARATOR, from, before,
                zoneId);
    }

    public int insertProgramEnding(LocalDate from, LocalDate to, Instant occurredAt) {
        return jdbc.update(INSERT_PROGRAM_ENDING, Timestamp.from(occurredAt), KEY_SEPARATOR, KEY_SEPARATOR, from, to);
    }

    /** Блокировка ежедневной задачи до конца транзакции; {@code false} — её уже выполняет другой экземпляр. */
    public boolean tryLockDaily(String name) {
        return Boolean.TRUE.equals(jdbc.queryForObject(TRY_LOCK_DAILY, Boolean.class, name));
    }

    public List<EventRow> feed(FeedQuery query) {
        var before = query.before() == null ? null : Timestamp.from(query.before());
        return jdbc.query(FEED, CoachEventRepository::event, query.coachId(), query.athleteId(), query.athleteId(),
                query.unseenOnly(), before, before, query.beforeId(), before, query.beforeId(), query.limit());
    }

    public long unseenCount(UUID coachId, UUID athleteId) {
        return jdbc.queryForObject(UNSEEN_COUNT, Long.class, coachId, athleteId, athleteId);
    }

    public int markSeen(UUID coachId, List<UUID> eventIds, UUID athleteId, UUID subjectId, Instant seenAt) {
        var ids = eventIds == null ? null : eventIds.toArray(UUID[]::new);
        return jdbc.update(MARK_SEEN, Timestamp.from(seenAt), coachId, ids, ids, athleteId, athleteId, subjectId,
                subjectId);
    }

    private static EventRow event(ResultSet row, int rowNumber) throws SQLException {
        return new EventRow(row.getObject("id", UUID.class), row.getObject("athlete_user_id", UUID.class),
                row.getString("display_name"), CoachEventKind.fromCode(row.getString("kind")),
                row.getObject("subject_id", UUID.class), row.getObject("subject_date", LocalDate.class),
                row.getString("title"), instant(row, "occurred_at"), instant(row, "seen_at"));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        var value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
