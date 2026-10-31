package com.sizylift.backend.sync.service;

import java.util.Map;
import java.util.UUID;

import com.sizylift.backend.access.service.AccessPolicy;
import com.sizylift.backend.notifications.entity.NotificationKind;
import com.sizylift.backend.notifications.service.NotificationQueue;
import com.sizylift.backend.publication.service.AggregateChange;
import com.sizylift.backend.publication.service.AggregateType;
import org.springframework.stereotype.Component;

/**
 * Какие записи агрегатов дают уведомление второй стороне связи (ADR-0009, «Контекст»):
 * <ul>
 *   <li>подопечному — тренер записал его программу или оставил комментарий;</li>
 *   <li>тренеру — подопечный завершил новую тренировку, перенёс тренировку или оставил комментарий.</li>
 * </ul>
 * Удаления, записи сервера и правки собственных данных без связи уведомлений не дают.
 */
@Component
class ChangeNotifications {

    private final AccessPolicy access;
    private final NotificationQueue queue;

    ChangeNotifications(AccessPolicy access, NotificationQueue queue) {
        this.access = access;
        this.queue = queue;
    }

    void onChange(AggregateChange change) {
        if (change.deleted() || change.writerId() == null) {
            return;
        }
        switch (change.type()) {
            case AggregateType.PROGRAM -> {
                if (!change.byOwner()) {
                    notifyOwner(change, NotificationKind.PROGRAM_UPDATED);
                }
            }
            case AggregateType.STANDALONE_WORKOUT -> {
                if (!change.byOwner()) {
                    notifyOwner(change, NotificationKind.WORKOUT_PLANNED);
                }
            }
            case AggregateType.WORKOUT -> {
                if (change.byOwner() && change.created()) {
                    notifyCoach(change, NotificationKind.WORKOUT_COMPLETED);
                }
            }
            case AggregateType.WORKOUT_RESCHEDULE -> notifyCoach(change, NotificationKind.WORKOUT_RESCHEDULED);
            case AggregateType.WORKOUT_COMMENT -> {
                if (!change.created()) {
                    return;
                }
                if (change.byOwner()) {
                    notifyCoach(change, NotificationKind.COMMENT_ADDED);
                } else {
                    notifyOwner(change, NotificationKind.COMMENT_ADDED);
                }
            }
            default -> {
            }
        }
    }

    private void notifyOwner(AggregateChange change, NotificationKind kind) {
        queue.enqueue(change.ownerId(), kind, change.aggregateId(), data(change, change.writerId()));
    }

    /** Только при активной связи и если запись сделал сам подопечный. */
    private void notifyCoach(AggregateChange change, NotificationKind kind) {
        if (change.byOwner()) {
            access.activeCoach(change.ownerId()).ifPresent(coach ->
                    queue.enqueue(coach, kind, change.aggregateId(), data(change, change.ownerId())));
        }
    }

    private static Map<String, String> data(AggregateChange change, UUID actorId) {
        return Map.of(NotificationQueue.OWNER_ID, change.ownerId().toString(),
                NotificationQueue.AGGREGATE_ID, change.aggregateId().toString(),
                NotificationQueue.ACTOR_ID, actorId.toString());
    }
}
