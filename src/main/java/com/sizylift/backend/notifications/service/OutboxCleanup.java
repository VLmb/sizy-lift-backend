package com.sizylift.backend.notifications.service;

import java.time.Duration;

import com.sizylift.backend.notifications.repository.NotificationOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Удаляет отправленные и брошенные строки outbox старше 7 дней. */
@Component
public class OutboxCleanup {

    static final Duration RETENTION = Duration.ofDays(7);
    private static final String DAILY_AT_NIGHT = "0 37 3 * * *";
    private static final Logger log = LoggerFactory.getLogger(OutboxCleanup.class);

    private final NotificationOutboxRepository outbox;

    OutboxCleanup(NotificationOutboxRepository outbox) {
        this.outbox = outbox;
    }

    @Scheduled(cron = DAILY_AT_NIGHT)
    public int purge() {
        var removed = outbox.deleteFinishedOlderThan(RETENTION);
        log.info("Purged {} finished notifications", removed);
        return removed;
    }
}
