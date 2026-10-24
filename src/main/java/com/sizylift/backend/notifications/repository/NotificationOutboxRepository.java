package com.sizylift.backend.notifications.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.sizylift.backend.notifications.entity.NotificationKind;
import com.sizylift.backend.notifications.entity.PendingNotification;
import com.sizylift.backend.notifications.entity.TicketRef;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Транзакционный outbox уведомлений. */
@Repository
public class NotificationOutboxRepository {

    private static final String ENQUEUE = """
            INSERT INTO sizylift.notification_outbox (user_id, kind, data, dedup_key) VALUES (?, ?, ?::jsonb, ?)
            ON CONFLICT (user_id, dedup_key) WHERE sent_at IS NULL AND gave_up_at IS NULL DO NOTHING
            """;
    private static final String CLAIM_DUE = """
            UPDATE sizylift.notification_outbox SET next_attempt_at = now() + make_interval(secs => ?)
            WHERE id IN (SELECT id FROM sizylift.notification_outbox
                         WHERE sent_at IS NULL AND gave_up_at IS NULL AND next_attempt_at <= now()
                         ORDER BY next_attempt_at LIMIT ? FOR UPDATE SKIP LOCKED)
            RETURNING id, user_id, kind, data, attempts
            """;
    private static final String MARK_SENT = """
            UPDATE sizylift.notification_outbox SET sent_at = now(), attempts = attempts + 1, ticket_ids = ?::jsonb
            WHERE id = ?
            """;
    private static final String RETRY = """
            UPDATE sizylift.notification_outbox SET attempts = attempts + 1, next_attempt_at = ? WHERE id = ?
            """;
    private static final String GIVE_UP = """
            UPDATE sizylift.notification_outbox SET attempts = attempts + 1, gave_up_at = now() WHERE id = ?
            """;
    private static final String CLAIM_RECEIPTS = """
            UPDATE sizylift.notification_outbox SET receipts_checked_at = now()
            WHERE id IN (SELECT id FROM sizylift.notification_outbox
                         WHERE receipts_checked_at IS NULL AND ticket_ids IS NOT NULL
                           AND sent_at <= now() - make_interval(secs => ?)
                           AND sent_at > now() - make_interval(secs => ?)
                         ORDER BY sent_at LIMIT ? FOR UPDATE SKIP LOCKED)
            RETURNING ticket_ids
            """;
    private static final String DELETE_FINISHED = """
            DELETE FROM sizylift.notification_outbox
            WHERE sent_at < now() - make_interval(secs => ?) OR gave_up_at < now() - make_interval(secs => ?)
            """;
    private static final String QUEUE_SIZE = """
            SELECT count(*) FROM sizylift.notification_outbox WHERE sent_at IS NULL AND gave_up_at IS NULL
            """;
    private static final String OLDEST_PENDING = """
            SELECT min(created_at) FROM sizylift.notification_outbox WHERE sent_at IS NULL AND gave_up_at IS NULL
            """;
    private static final TypeReference<Map<String, String>> DATA = new TypeReference<>() {
    };
    private static final TypeReference<List<TicketRef>> TICKETS = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public NotificationOutboxRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Возвращает {@code false}, если такое же уведомление уже ждёт отправки (склейка). */
    public boolean enqueue(UUID userId, NotificationKind kind, Map<String, String> data, String dedupKey) {
        return jdbc.update(ENQUEUE, userId, kind.code(), json.writeValueAsString(data), dedupKey) == 1;
    }

    /**
     * Забирает до {@code limit} строк, которым пора на отправку, и откладывает их на {@code lease}: если процесс
     * упадёт во время отправки, строки вернутся в очередь. Параллельные экземпляры берут разные строки.
     */
    public List<PendingNotification> claimDue(int limit, Duration lease) {
        return jdbc.query(CLAIM_DUE, this::pending, lease.toSeconds(), limit);
    }

    public void markSent(UUID id, List<TicketRef> tickets) {
        jdbc.update(MARK_SENT, tickets.isEmpty() ? null : json.writeValueAsString(tickets), id);
    }

    public void retryAt(UUID id, Instant nextAttemptAt) {
        jdbc.update(RETRY, nextAttemptAt.atOffset(ZoneOffset.UTC), id);
    }

    public void giveUp(UUID id) {
        jdbc.update(GIVE_UP, id);
    }

    /** Тикеты отправленных строк, квитанции которых пора проверить; строки помечаются проверенными. */
    public List<TicketRef> claimReceipts(Duration minAge, Duration maxAge, int limit) {
        return jdbc.query(CLAIM_RECEIPTS, (row, n) -> json.readValue(row.getString("ticket_ids"), TICKETS),
                        minAge.toSeconds(), maxAge.toSeconds(), limit).stream()
                .flatMap(List::stream)
                .toList();
    }

    public int deleteFinishedOlderThan(Duration age) {
        return jdbc.update(DELETE_FINISHED, age.toSeconds(), age.toSeconds());
    }

    public long queueSize() {
        return jdbc.queryForObject(QUEUE_SIZE, Long.class);
    }

    /** Возраст самой старой неотправленной строки; {@link Duration#ZERO} при пустой очереди. */
    public Duration oldestPendingAge(Instant now) {
        var oldest = jdbc.queryForObject(OLDEST_PENDING, OffsetDateTime.class);
        return oldest == null ? Duration.ZERO : Duration.between(oldest.toInstant(), now);
    }

    private PendingNotification pending(ResultSet row, int rowNumber) throws SQLException {
        return new PendingNotification(row.getObject("id", UUID.class), row.getObject("user_id", UUID.class),
                NotificationKind.of(row.getString("kind")), json.readValue(row.getString("data"), DATA),
                row.getInt("attempts"));
    }
}
