package com.sizylift.backend.publication.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;

/** Общие помощники JDBC для репозиториев публикации. */
final class Sql {

    private Sql() {
    }

    /** Драйвер PostgreSQL не связывает {@link Instant} напрямую. */
    static OffsetDateTime timestamp(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    static Instant instant(ResultSet row, String column) throws SQLException {
        var value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    static LocalDate date(ResultSet row, String column) throws SQLException {
        return row.getObject(column, LocalDate.class);
    }

    static UUID uuid(ResultSet row, String column) throws SQLException {
        return row.getObject(column, UUID.class);
    }

    /** {@code null} для SQL NULL, в отличие от {@link ResultSet#getInt}. */
    static Integer integer(ResultSet row, String column) throws SQLException {
        return row.getObject(column, Integer.class);
    }

    static <T> Optional<T> first(List<T> rows) {
        return rows.stream().findFirst();
    }

    /** Группирует дочерние строки по родителю, сохраняя порядок выборки. */
    static <K, T> Map<K, List<T>> byParent(List<Child<K, T>> rows) {
        return rows.stream().collect(Collectors.groupingBy(Child::parentId, java.util.LinkedHashMap::new,
                Collectors.mapping(Child::value, Collectors.toList())));
    }

    static <K, T> List<T> children(Map<K, List<T>> byParent, K parentId) {
        return byParent.getOrDefault(parentId, List.of());
    }

    /** Строка дочерней таблицы, ещё не собранная в родителя. */
    record Child<K, T>(K parentId, T value) {
    }

    static <T, R> List<R> map(List<T> values, Function<T, R> mapper) {
        return values.stream().map(mapper).toList();
    }

    static void batch(JdbcTemplate jdbc, String sql, List<Object[]> rows) {
        if (!rows.isEmpty()) {
            jdbc.batchUpdate(sql, rows);
        }
    }
}
