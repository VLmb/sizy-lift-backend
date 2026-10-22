package com.sizylift.backend.imports.service;

import com.sizylift.backend.imports.repository.ImportSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Удаляет сессии импорта старше суток: это рабочий черновик, а не данные пользователя. */
@Component
public class ImportSessionCleanup {

    private static final String HOURLY = "0 7 * * * *";
    private static final Logger log = LoggerFactory.getLogger(ImportSessionCleanup.class);

    private final ImportSessionRepository sessions;

    ImportSessionCleanup(ImportSessionRepository sessions) {
        this.sessions = sessions;
    }

    @Scheduled(cron = HOURLY)
    public int purge() {
        var removed = sessions.deleteExpired();
        log.info("Purged {} expired import sessions", removed);
        return removed;
    }
}
