package com.sizylift.backend.notifications.repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Имена из опубликованных профилей — для текста уведомления «кто». */
@Repository
public class DisplayNameRepository {

    private static final String NAMES = """
            SELECT user_id, display_name FROM sizylift.athlete_profile
            WHERE user_id = ANY (?) AND display_name IS NOT NULL
            """;

    private final JdbcTemplate jdbc;

    public DisplayNameRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<UUID, String> namesOf(List<UUID> userIds) {
        var names = new HashMap<UUID, String>();
        if (!userIds.isEmpty()) {
            jdbc.query(NAMES, row -> {
                names.put(row.getObject("user_id", UUID.class), row.getString("display_name"));
            }, (Object) userIds.toArray(UUID[]::new));
        }
        return names;
    }
}
