package com.sizylift.backend.web.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.sizylift.backend.publication.entity.LiftClass;
import com.sizylift.backend.web.dto.MaxCandidateDtos.MaxCandidateSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Выполненные подходы, по которым клиент считает расчётный ПМ. Права проверяет сервис до вызова. */
@Repository
public class MaxCandidateRepository {

    /** Повторений больше — расчётный ПМ по подходу слишком неточен. */
    private static final int MAX_REPETITIONS = 12;
    private static final int MIN_REPETITIONS = 1;
    private static final String COMPLETED = "completed";

    private static final String SELECT_CANDIDATES = """
            SELECT we.exercise_id, w.id AS workout_id, w.workout_date, s.weight_grams, s.repetitions, s.rpe_tenths
            FROM sizylift.workout_set s
            JOIN sizylift.workout_exercise we ON we.user_id = s.user_id AND we.id = s.workout_exercise_id
            JOIN sizylift.workout w ON w.user_id = s.user_id AND w.id = s.workout_id
            JOIN sizylift.exercise e ON e.id = we.exercise_id
            WHERE s.user_id = ? AND w.workout_date >= ? AND s.completion_status = ?
              AND s.result_type = ? AND e.lift_class IN (?, ?)
              AND s.weight_grams > 0 AND s.repetitions BETWEEN ? AND ?
            ORDER BY w.workout_date DESC, w.completed_at DESC, w.id, we.position, s.position
            """;

    private final JdbcTemplate jdbc;

    public MaxCandidateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Подходы упражнений с ПМ ({@code sbd}, {@code basic}) с {@code from} включительно, новые сначала. */
    public List<MaxCandidateSet> completedSince(UUID owner, LocalDate from) {
        return jdbc.query(SELECT_CANDIDATES, (row, n) -> candidate(row), owner, from, COMPLETED,
                LiftClass.ONE_REP_MAX_RESULT_TYPE, LiftClass.SBD.code(), LiftClass.BASIC.code(), MIN_REPETITIONS,
                MAX_REPETITIONS);
    }

    private static MaxCandidateSet candidate(ResultSet row) throws SQLException {
        return new MaxCandidateSet(row.getObject("exercise_id", UUID.class), row.getObject("workout_id", UUID.class),
                row.getObject("workout_date", LocalDate.class), row.getInt("weight_grams"), row.getInt("repetitions"),
                row.getObject("rpe_tenths", Integer.class));
    }
}
