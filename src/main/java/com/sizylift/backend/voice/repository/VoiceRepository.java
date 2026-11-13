package com.sizylift.backend.voice.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.sizylift.backend.voice.service.VoiceCatalog.UserAlias;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class VoiceRepository {
    private static final String SELECT_SYSTEM_ALIASES =
            "SELECT exercise_id, alias FROM sizylift.voice_exercise_alias ORDER BY exercise_id, alias";
    private static final String SELECT_USER_ALIASES = """
            SELECT exercise_id, alias FROM sizylift.voice_user_alias
            WHERE user_id = ? ORDER BY updated_at DESC LIMIT ?
            """;
    private static final String UPSERT_USER_ALIAS = """
            INSERT INTO sizylift.voice_user_alias (user_id, alias_key, alias, exercise_id, updated_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (user_id, alias_key) DO UPDATE
            SET alias = EXCLUDED.alias, exercise_id = EXCLUDED.exercise_id, updated_at = EXCLUDED.updated_at
            """;
    /** Атомарно: строка обновляется, только пока лимит не достигнут; пустой результат — лимит исчерпан. */
    private static final String CONSUME = """
            INSERT INTO sizylift.voice_daily_usage (user_id, usage_date, requests) VALUES (?, ?, 1)
            ON CONFLICT (user_id, usage_date) DO UPDATE SET requests = sizylift.voice_daily_usage.requests + 1
            WHERE sizylift.voice_daily_usage.requests < ?
            RETURNING requests
            """;

    private final JdbcTemplate jdbc;

    public VoiceRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Map<UUID, List<String>> systemAliases() {
        var out = new HashMap<UUID, List<String>>();
        jdbc.query(SELECT_SYSTEM_ALIASES, row -> {
            out.computeIfAbsent(row.getObject("exercise_id", UUID.class), k -> new ArrayList<>()).add(row.getString("alias"));
        });
        return out;
    }

    public List<UserAlias> userAliases(UUID user, int limit) {
        return jdbc.query(SELECT_USER_ALIASES, (row, n) -> new UserAlias(row.getObject("exercise_id", UUID.class),
                row.getString("alias")), user, limit);
    }

    public void saveUserAlias(UUID user, String key, String alias, UUID exercise, Instant at) {
        jdbc.update(UPSERT_USER_ALIAS, user, key, alias, exercise, OffsetDateTime.ofInstant(at, ZoneOffset.UTC));
    }

    /** Засчитывает распознавание; false — суточный лимит уже исчерпан. */
    public boolean consume(UUID user, LocalDate day, int limit) {
        return !jdbc.queryForList(CONSUME, Integer.class, user, day, limit).isEmpty();
    }
}
