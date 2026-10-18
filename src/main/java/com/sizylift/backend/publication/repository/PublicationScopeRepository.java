package com.sizylift.backend.publication.repository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import com.sizylift.backend.publication.service.PublicationScope;
import com.sizylift.backend.publication.service.PublicationScope.Programs;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PublicationScopeRepository {

    private final JdbcTemplate jdbc;

    public PublicationScopeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<PublicationScope> find(UUID userId) {
        return jdbc.query("""
                SELECT enabled, workouts, workouts_since, programs, profile, records, health
                FROM sizylift.publication_scope WHERE user_id = ?
                """, (row, n) -> new PublicationScope(
                row.getBoolean("enabled"),
                row.getBoolean("workouts"),
                row.getObject("workouts_since", LocalDate.class),
                Programs.fromCode(row.getString("programs")),
                row.getBoolean("profile"),
                row.getBoolean("records"),
                row.getBoolean("health")), userId).stream().findFirst();
    }

    public void save(UUID userId, PublicationScope scope) {
        jdbc.update("""
                INSERT INTO sizylift.publication_scope (user_id, enabled, workouts, workouts_since, programs, profile,
                                                        records, health, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (user_id) DO UPDATE SET
                    enabled = excluded.enabled, workouts = excluded.workouts,
                    workouts_since = excluded.workouts_since, programs = excluded.programs,
                    profile = excluded.profile, records = excluded.records, health = excluded.health,
                    updated_at = now()
                """, userId, scope.enabled(), scope.workouts(), scope.workoutsSince(), scope.programs().code(),
                scope.profile(), scope.records(), scope.health());
    }
}
