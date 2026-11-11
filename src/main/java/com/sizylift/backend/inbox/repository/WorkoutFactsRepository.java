package com.sizylift.backend.inbox.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.sizylift.backend.publication.service.AggregateType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Суть выполненной тренировки для строки события и строки подопечного (docs/coach-workspace.md, раздел 7):
 * счёт подходов плана, оценка, самый значимый рекорд и комментарии подопечного. RPE, вес и тоннаж сюда
 * намеренно не попадают.
 */
@Repository
public class WorkoutFactsRepository {

    private static final String COMPLETED = "completed";
    /**
     * Подход плана выполнен, если с его снимком сопоставлен выполненный подход факта — так же, как веб сравнивает
     * план и факт (по {@code actual_set_id} или {@code source_planned_set_id}). Рекорд: рекорд веса важнее
     * рекорда e1RM, затем больший прирост к прошлому значению в процентах, рекорд без прошлого значения — после
     * рекордов с приростом, при равенстве — порядок в тренировке.
     */
    private static final String FACTS = """
            SELECT w.user_id, w.id, w.quality,
                   (SELECT count(*) FROM sizylift.workout_plan_set_snapshot ps
                    WHERE ps.user_id = w.user_id AND ps.workout_id = w.id) AS planned_sets,
                   (SELECT count(*) FROM sizylift.workout_plan_set_snapshot ps
                    WHERE ps.user_id = w.user_id AND ps.workout_id = w.id
                      AND EXISTS (SELECT 1 FROM sizylift.workout_set s
                                  WHERE s.user_id = ps.user_id AND s.workout_id = ps.workout_id
                                    AND s.completion_status = ?
                                    AND (s.id = ps.actual_set_id
                                         OR s.source_planned_set_id = ps.source_planned_set_id))) AS done_sets,
                   (SELECT c.text FROM sizylift.workout_comment c
                    JOIN sizylift.published_aggregate a ON a.user_id = c.user_id AND a.type = ?
                        AND a.aggregate_id = c.id AND NOT a.deleted AND a.author_user_id IS NULL
                    WHERE c.user_id = w.user_id AND c.workout_id = w.id AND c.at_completion
                    ORDER BY c.created_at, c.id LIMIT 1) AS completion_comment,
                   EXISTS (SELECT 1 FROM sizylift.workout_comment c
                           JOIN sizylift.published_aggregate a ON a.user_id = c.user_id AND a.type = ?
                               AND a.aggregate_id = c.id AND NOT a.deleted AND a.author_user_id IS NULL
                           WHERE c.user_id = w.user_id AND c.workout_id = w.id) AS has_comment,
                   r.record_type, r.exercise_name, r.exercise_short_name, r.result_type, r.weight_grams,
                   r.repetitions
            FROM sizylift.workout w
            JOIN unnest(?::uuid[], ?::uuid[]) AS k(user_id, id) ON w.user_id = k.user_id AND w.id = k.id
            LEFT JOIN LATERAL (
                SELECT e.record_type, we.exercise_name, we.exercise_short_name, s.result_type, s.weight_grams,
                       s.repetitions
                FROM sizylift.workout_record_event e
                JOIN sizylift.workout_set s ON s.user_id = e.user_id AND s.id = e.actual_set_id
                JOIN sizylift.workout_exercise we ON we.user_id = e.user_id AND we.id = e.workout_exercise_id
                JOIN sizylift.workout_block b ON b.user_id = we.user_id AND b.id = we.block_id
                WHERE e.user_id = w.user_id AND e.workout_id = w.id
                ORDER BY (e.record_type = 'maximum_weight') DESC,
                         (e.previous_value IS NULL OR e.previous_value = 0),
                         (e.value - e.previous_value)::numeric / nullif(e.previous_value, 0) DESC NULLS LAST,
                         b.position, we.position, s.position
                LIMIT 1
            ) r ON true
            """;

    private final JdbcTemplate jdbc;

    public WorkoutFactsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Тренировка владельца. */
    public record WorkoutKey(UUID ownerId, UUID workoutId) {
    }

    /** Рекорд строкой: упражнение и подход, на котором он поставлен. */
    public record RecordFact(String recordType, String exerciseName, String exerciseShortName, String resultType,
                             Integer weightGrams, Integer repetitions) {
    }

    /**
     * {@code completionComment} — комментарий подопечного, оставленный при завершении; {@code hasComment} —
     * у тренировки есть хотя бы один комментарий подопечного.
     */
    public record Facts(int plannedSets, int doneSets, String quality, RecordFact record,
                        String completionComment, boolean hasComment) {
    }

    /** Факты найденных тренировок; удалённых в ответе нет. */
    public Map<WorkoutKey, Facts> facts(Collection<WorkoutKey> keys) {
        var result = new HashMap<WorkoutKey, Facts>();
        if (keys.isEmpty()) {
            return result;
        }
        var owners = keys.stream().map(WorkoutKey::ownerId).toArray(UUID[]::new);
        var workouts = keys.stream().map(WorkoutKey::workoutId).toArray(UUID[]::new);
        jdbc.query(FACTS, row -> {
            result.put(new WorkoutKey(uuid(row, "user_id"), uuid(row, "id")), facts(row));
        }, COMPLETED, AggregateType.WORKOUT_COMMENT, AggregateType.WORKOUT_COMMENT, owners, workouts);
        return result;
    }

    private static Facts facts(ResultSet row) throws SQLException {
        return new Facts(row.getInt("planned_sets"), row.getInt("done_sets"), row.getString("quality"), record(row),
                row.getString("completion_comment"), row.getBoolean("has_comment"));
    }

    private static RecordFact record(ResultSet row) throws SQLException {
        var type = row.getString("record_type");
        return type == null ? null : new RecordFact(type, row.getString("exercise_name"),
                row.getString("exercise_short_name"), row.getString("result_type"),
                row.getObject("weight_grams", Integer.class), row.getObject("repetitions", Integer.class));
    }

    private static UUID uuid(ResultSet row, String column) throws SQLException {
        return row.getObject(column, UUID.class);
    }
}
