package com.sizylift.backend.admin.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Маркер сценария и блокировка, общая с обычной записью данных владельца. */
@Repository
public class AdminSampleDataRepository {
    private static final String LOCK = "SELECT id FROM sizylift.user_account WHERE id = ? FOR UPDATE";
    private static final String FIND = "SELECT summary::text FROM sizylift.admin_sample_data WHERE user_id = ?";
    private static final String EXISTS = """
            SELECT EXISTS (SELECT 1 FROM sizylift.published_aggregate
            WHERE user_id = ? AND type = ? AND aggregate_id = ?)
            """;
    private static final String INSERT = """
            INSERT INTO sizylift.admin_sample_data (user_id, scenario, created_by_subject, summary)
            VALUES (?, ?, ?, ?::jsonb)
            """;
    private final JdbcTemplate jdbc;

    public AdminSampleDataRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean lock(UUID owner) {
        return !jdbc.query(LOCK, (row, number) -> row.getObject("id", UUID.class), owner).isEmpty();
    }

    public Optional<String> find(UUID owner) {
        return jdbc.query(FIND, (row, number) -> row.getString(1), owner).stream().findFirst();
    }

    public boolean exists(UUID owner, String type, UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(EXISTS, Boolean.class, owner, type, id));
    }

    public void insert(UUID owner, String scenario, String actor, String summary) {
        jdbc.update(INSERT, owner, scenario, actor, summary);
    }
}
