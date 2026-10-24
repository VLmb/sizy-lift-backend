package com.sizylift.backend.notifications.service;

import java.util.Map;
import java.util.UUID;

import com.sizylift.backend.notifications.entity.NotificationKind;
import com.sizylift.backend.notifications.repository.NotificationOutboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Постановка уведомления в той же транзакции, что и изменение данных (ADR-0009, раздел 3): нет коммита —
 * нет уведомления. Вне транзакции вызов запрещён. О тренировках и программах сервис ничего не знает.
 */
@Service
public class NotificationQueue {

    /** Ключи {@code data}: только идентификаторы. */
    public static final String OWNER_ID = "ownerId";
    public static final String AGGREGATE_ID = "aggregateId";
    public static final String ACTOR_ID = "actorId";
    public static final String LINK_ID = "linkId";
    private static final String KEY_SEPARATOR = ":";

    private final NotificationOutboxRepository outbox;
    private final NotificationMetrics metrics;

    NotificationQueue(NotificationOutboxRepository outbox, NotificationMetrics metrics) {
        this.outbox = outbox;
        this.metrics = metrics;
    }

    /**
     * Ставит уведомление получателю. Если такое же ({@code kind} по {@code subjectId}) уже ждёт отправки,
     * второе не ставится: тренер, трижды поправивший программу за минуту, даёт одно уведомление.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(UUID recipientId, NotificationKind kind, UUID subjectId, Map<String, String> data) {
        var queued = outbox.enqueue(recipientId, kind, data, kind.code() + KEY_SEPARATOR + subjectId);
        metrics.enqueued(kind, queued);
    }
}
