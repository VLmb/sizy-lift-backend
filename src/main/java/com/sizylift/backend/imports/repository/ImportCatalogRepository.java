package com.sizylift.backend.imports.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.sizylift.backend.imports.entity.CatalogExercise;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Упражнения, доступные для сопоставления, и 1ПМ владельца программы. */
@Repository
public class ImportCatalogRepository {

    private static final String EXERCISES = """
            SELECT id, name, short_name, result_type FROM sizylift.exercise
            WHERE user_id IS NULL OR user_id = ANY (?) ORDER BY user_id NULLS FIRST, name
            """;
    private static final String SIMILAR = """
            SELECT id, name, short_name, result_type, public.similarity(lower(name), ?) AS score
            FROM sizylift.exercise
            WHERE user_id IS NULL OR user_id = ANY (?)
            ORDER BY score DESC, name LIMIT ?
            """;
    private static final String ONE_REP_MAXES =
            "SELECT exercise_id, weight_grams FROM sizylift.one_rep_max WHERE user_id = ?";

    private final JdbcTemplate jdbc;

    public ImportCatalogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Системный каталог и свои упражнения пользователей {@code owners}. */
    public List<CatalogExercise> exercises(List<UUID> owners) {
        return jdbc.query(EXERCISES, (row, n) -> exercise(row), (Object) owners.toArray(UUID[]::new));
    }

    /** Самые похожие по триграммам ({@code pg_trgm}) названия, с оценкой 0…1. */
    public List<Scored> similar(String name, List<UUID> owners, int limit) {
        return jdbc.query(SIMILAR, (row, n) -> new Scored(exercise(row), row.getDouble("score")), name,
                owners.toArray(UUID[]::new), limit);
    }

    public Map<UUID, Integer> oneRepMaxGrams(UUID userId) {
        var result = new HashMap<UUID, Integer>();
        jdbc.query(ONE_REP_MAXES, row -> {
            result.put(row.getObject("exercise_id", UUID.class), row.getInt("weight_grams"));
        }, userId);
        return result;
    }

    private static CatalogExercise exercise(ResultSet row) throws SQLException {
        return new CatalogExercise(row.getObject("id", UUID.class), row.getString("name"),
                row.getString("short_name"), row.getString("result_type"));
    }

    public record Scored(CatalogExercise exercise, double score) {
    }
}
