package com.sizylift.backend.notifications.repository;

import java.util.List;
import java.util.UUID;

import com.sizylift.backend.notifications.entity.PushTarget;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Устройства и их push-токены. */
@Repository
public class DeviceRepository {

    private static final String UPSERT = """
            INSERT INTO sizylift.device (id, user_id, platform, push_token) VALUES (?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET user_id = EXCLUDED.user_id, platform = EXCLUDED.platform,
                push_token = EXCLUDED.push_token, last_seen_at = now()
            """;
    private static final String RELEASE_TOKEN =
            "UPDATE sizylift.device SET push_token = NULL WHERE push_token = ? AND id <> ?";
    private static final String DELETE_OWN = "DELETE FROM sizylift.device WHERE id = ? AND user_id = ?";
    private static final String TARGETS = """
            SELECT user_id, id, push_token FROM sizylift.device
            WHERE user_id = ANY (?) AND push_token IS NOT NULL ORDER BY id
            """;
    private static final String CLEAR_TOKEN =
            "UPDATE sizylift.device SET push_token = NULL WHERE id = ? AND push_token = ?";

    private final JdbcTemplate jdbc;

    public DeviceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Регистрирует установку приложения за пользователем. Тот же id после входа другим аккаунтом переходит
     * к новому пользователю; токен снимается с других установок, чтобы он не получал чужие уведомления.
     */
    public void register(UUID deviceId, UUID userId, String platform, String pushToken) {
        if (pushToken != null) {
            jdbc.update(RELEASE_TOKEN, pushToken, deviceId);
        }
        jdbc.update(UPSERT, deviceId, userId, platform, pushToken);
    }

    public void deleteOwn(UUID deviceId, UUID userId) {
        jdbc.update(DELETE_OWN, deviceId, userId);
    }

    public List<PushTarget> targets(List<UUID> userIds) {
        return jdbc.query(TARGETS, (row, n) -> new PushTarget(row.getObject("user_id", UUID.class),
                row.getObject("id", UUID.class), row.getString("push_token")), (Object) userIds.toArray(UUID[]::new));
    }

    /** Обнуляет токен, только если он не сменился с момента отправки. */
    public void clearToken(UUID deviceId, String pushToken) {
        jdbc.update(CLEAR_TOKEN, deviceId, pushToken);
    }
}
