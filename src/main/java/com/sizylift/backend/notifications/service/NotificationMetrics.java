package com.sizylift.backend.notifications.service;

import java.time.Clock;

import com.sizylift.backend.notifications.entity.NotificationKind;
import com.sizylift.backend.notifications.repository.NotificationOutboxRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Метрики уведомлений (docs/observability.md); теги — вид и итог, без идентификаторов. */
@Component
class NotificationMetrics {

    static final String ENQUEUED_METER = "sizylift.notifications.enqueued";
    static final String SENT_METER = "sizylift.notifications.sent";
    static final String RECEIPTS_METER = "sizylift.notifications.receipts";
    static final String QUEUE_SIZE_METER = "sizylift.notifications.queue.size";
    static final String OLDEST_AGE_METER = "sizylift.notifications.queue.oldest.age";

    /** Итог строки outbox после попытки отправки. */
    enum Outcome {
        SENT("sent"),
        NO_DEVICE("no_device"),
        FAILED("failed"),
        RETRY("retry"),
        GAVE_UP("gave_up");

        private final String code;

        Outcome(String code) {
            this.code = code;
        }
    }

    /** Итог квитанции. */
    enum ReceiptOutcome {
        OK("ok"),
        DEVICE_NOT_REGISTERED("device_not_registered"),
        ERROR("error"),
        UNAVAILABLE("unavailable");

        private final String code;

        ReceiptOutcome(String code) {
            this.code = code;
        }
    }

    private final MeterRegistry meters;

    NotificationMetrics(MeterRegistry meters, NotificationOutboxRepository outbox, Clock clock) {
        this.meters = meters;
        Gauge.builder(QUEUE_SIZE_METER, outbox, NotificationOutboxRepository::queueSize)
                .description("Notifications waiting to be sent")
                .register(meters);
        Gauge.builder(OLDEST_AGE_METER, outbox, repository -> repository.oldestPendingAge(clock.instant())
                        .toSeconds())
                .description("Age of the oldest unsent notification")
                .baseUnit("seconds")
                .register(meters);
    }

    void enqueued(NotificationKind kind, boolean queued) {
        meters.counter(ENQUEUED_METER, "kind", kind.code(), "coalesced", String.valueOf(!queued)).increment();
    }

    void sent(NotificationKind kind, Outcome outcome) {
        meters.counter(SENT_METER, "kind", kind.code(), "outcome", outcome.code).increment();
    }

    void receipt(ReceiptOutcome outcome) {
        meters.counter(RECEIPTS_METER, "outcome", outcome.code).increment();
    }
}
