package com.sizylift.backend.publication.service;

import java.time.Clock;
import java.time.Duration;

import com.sizylift.backend.publication.repository.AggregateConflictRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Удаляет записи журнала конфликтов старше срока хранения. */
@Component
public class ConflictRetention {

    static final Duration RETENTION = Duration.ofDays(30);
    private static final String DAILY_AT_NIGHT = "0 17 3 * * *";
    private static final Logger log = LoggerFactory.getLogger(ConflictRetention.class);

    private final AggregateConflictRepository conflicts;
    private final Clock clock;

    ConflictRetention(AggregateConflictRepository conflicts, Clock clock) {
        this.conflicts = conflicts;
        this.clock = clock;
    }

    @Scheduled(cron = DAILY_AT_NIGHT)
    public int purge() {
        var removed = conflicts.deleteOlderThan(clock.instant().minus(RETENTION));
        log.info("Purged {} expired aggregate conflicts", removed);
        return removed;
    }
}
