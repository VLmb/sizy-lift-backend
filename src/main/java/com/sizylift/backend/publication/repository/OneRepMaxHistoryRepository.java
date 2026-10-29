package com.sizylift.backend.publication.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.sizylift.backend.publication.dto.payload.OneRepMaxPayloadV1;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import static com.sizylift.backend.publication.repository.Sql.date;
import static com.sizylift.backend.publication.repository.Sql.instant;

/** История фактического 1ПМ (changeset 023): точка на каждое принятое новое значение агрегата. */
@Repository
public class OneRepMaxHistoryRepository {

    /** Повтор того же значения (правка только {@code updatedAt}, повторная отправка) новой точки не даёт. */
    private static final String INSERT = """
            INSERT INTO sizylift.one_rep_max_history (user_id, exercise_id, weight_grams, achieved_on, recorded_at)
            SELECT ?, ?, ?, ?, now()
            WHERE NOT EXISTS (
                SELECT 1 FROM (SELECT h.weight_grams, h.achieved_on FROM sizylift.one_rep_max_history h
                               WHERE h.user_id = ? AND h.exercise_id = ?
                               ORDER BY h.recorded_at DESC, h.id DESC LIMIT 1) last
                WHERE last.weight_grams = ? AND last.achieved_on = ?)
            """;
    private static final String DELETE =
            "DELETE FROM sizylift.one_rep_max_history WHERE user_id = ? AND exercise_id = ?";
    private static final String SELECT = """
            SELECT weight_grams, achieved_on, recorded_at FROM sizylift.one_rep_max_history
            WHERE user_id = ? AND exercise_id = ?
            ORDER BY recorded_at DESC, id DESC
            """;

    private final JdbcTemplate jdbc;

    public OneRepMaxHistoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Entry(int weightGrams, LocalDate achievedOn, Instant recordedAt) {
    }

    public void record(UUID userId, UUID exerciseId, OneRepMaxPayloadV1 value) {
        jdbc.update(INSERT, userId, exerciseId, value.weightGrams(), value.achievedOn(), userId, exerciseId,
                value.weightGrams(), value.achievedOn());
    }

    public void delete(UUID userId, UUID exerciseId) {
        jdbc.update(DELETE, userId, exerciseId);
    }

    /** Новые сначала. */
    public List<Entry> history(UUID userId, UUID exerciseId) {
        return jdbc.query(SELECT, (row, n) -> new Entry(row.getInt("weight_grams"), date(row, "achieved_on"),
                instant(row, "recorded_at")), userId, exerciseId);
    }
}
