package com.sizylift.backend.admin.repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AdminUserRepository {
    private static final String SUMMARY = """
            SELECT a.id, a.subject, a.coach_enabled, a.created_at, a.last_seen_at,
                (SELECT count(*) FROM sizylift.workout w WHERE w.user_id = a.id) AS workouts,
                (SELECT count(*) FROM sizylift.program p WHERE p.user_id = a.id) AS programs,
                (SELECT count(*) FROM sizylift.coaching_link l
                    WHERE l.coach_user_id = a.id AND l.ended_at IS NULL) AS athletes,
                EXISTS (SELECT 1 FROM sizylift.coaching_link l
                    WHERE l.athlete_user_id = a.id AND l.ended_at IS NULL) AS has_coach
            FROM sizylift.user_account a WHERE a.issuer = ?
            """;
    private final JdbcTemplate jdbc;

    public AdminUserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Summary> summaries(String issuer) {
        return jdbc.query(SUMMARY, (row, number) -> new Summary(row.getString("subject"),
                row.getObject("id", UUID.class), row.getBoolean("coach_enabled"),
                instant(row.getObject("created_at", OffsetDateTime.class)),
                instant(row.getObject("last_seen_at", OffsetDateTime.class)),
                row.getLong("workouts"), row.getLong("programs"), row.getLong("athletes"),
                row.getBoolean("has_coach")), issuer).stream().collect(Collectors.toMap(Summary::subject, value -> value));
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    public record Summary(String subject, UUID id, boolean coach, Instant firstSeen, Instant lastSeen,
            long workouts, long programs, long athletes, boolean hasCoach) {
    }
}
