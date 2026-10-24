package com.sizylift.backend.notifications.service;

import java.util.Map;
import java.util.UUID;

import com.sizylift.backend.PostgresIntegrationTest;
import com.sizylift.backend.notifications.entity.NotificationKind;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Постановка в outbox (ADR-0009, раздел 3): только при коммите, со склейкой одинаковых. */
@SpringBootTest
class NotificationQueueTest extends PostgresIntegrationTest {

    @Autowired
    NotificationQueue queue;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    OutboxCleanup cleanup;

    @Test
    void notificationIsQueuedOnlyWhenTheTransactionCommits() {
        var user = newUser();
        var subject = UUID.randomUUID();

        try {
            transactions.executeWithoutResult(status -> {
                queue.enqueue(user, NotificationKind.PROGRAM_UPDATED, subject, data(subject));
                throw new IllegalStateException("изменение данных откатилось");
            });
        } catch (IllegalStateException expected) {
            // откат — уведомления быть не должно
        }
        assertThat(pending(user)).isZero();

        transactions.executeWithoutResult(status ->
                queue.enqueue(user, NotificationKind.PROGRAM_UPDATED, subject, data(subject)));
        assertThat(pending(user)).isEqualTo(1);
    }

    @Test
    void queueRequiresTheCallersTransaction() {
        var user = newUser();
        var subject = UUID.randomUUID();

        assertThatThrownBy(() -> queue.enqueue(user, NotificationKind.PROGRAM_UPDATED, subject, data(subject)))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(pending(user)).isZero();
    }

    @Test
    void samePendingNotificationIsCoalescedUntilItIsSent() {
        var user = newUser();
        var program = UUID.randomUUID();

        for (int i = 0; i < 3; i++) {
            transactions.executeWithoutResult(status ->
                    queue.enqueue(user, NotificationKind.PROGRAM_UPDATED, program, data(program)));
        }
        transactions.executeWithoutResult(status ->
                queue.enqueue(user, NotificationKind.COMMENT_ADDED, program, data(program)));
        assertThat(pending(user)).as("одно на вид и агрегат").isEqualTo(2);

        jdbc.update("UPDATE sizylift.notification_outbox SET sent_at = now() WHERE user_id = ?", user);
        transactions.executeWithoutResult(status ->
                queue.enqueue(user, NotificationKind.PROGRAM_UPDATED, program, data(program)));
        assertThat(pending(user)).as("после отправки — новое").isEqualTo(1);
    }

    @Test
    void finishedRowsOlderThanAWeekArePurged() {
        var user = newUser();
        var subject = UUID.randomUUID();
        transactions.executeWithoutResult(status ->
                queue.enqueue(user, NotificationKind.PROGRAM_UPDATED, subject, data(subject)));
        jdbc.update("UPDATE sizylift.notification_outbox SET sent_at = now() - interval '8 days' WHERE user_id = ?",
                user);
        transactions.executeWithoutResult(status ->
                queue.enqueue(user, NotificationKind.COMMENT_ADDED, subject, data(subject)));

        cleanup.purge();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM sizylift.notification_outbox WHERE user_id = ?",
                Long.class, user)).isEqualTo(1);
    }

    private static Map<String, String> data(UUID aggregateId) {
        return Map.of(NotificationQueue.AGGREGATE_ID, aggregateId.toString());
    }

    private long pending(UUID user) {
        return jdbc.queryForObject("SELECT count(*) FROM sizylift.notification_outbox WHERE user_id = ? "
                + "AND sent_at IS NULL", Long.class, user);
    }

    private UUID newUser() {
        return jdbc.queryForObject("INSERT INTO sizylift.user_account (issuer, subject) VALUES ('test', ?) "
                + "RETURNING id", UUID.class, UUID.randomUUID().toString());
    }
}
