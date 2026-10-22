package com.sizylift.backend.imports.service;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.sizylift.backend.imports.config.ImportProperties;
import com.sizylift.backend.imports.repository.ImportSessionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Ограничения импорта (ADR-0008, раздел 4): импортов в сутки на пользователя (по {@code import_session}),
 * одновременных импортов пользователя и общий {@link Semaphore} на одновременные вызовы модели — запросы
 * на виртуальных потоках не должны разом уйти к провайдеру.
 */
@Component
class ImportLimits {

    private static final Duration DAY = Duration.ofDays(1);

    private final ImportProperties properties;
    private final ImportSessionRepository sessions;
    private final Clock clock;
    private final Semaphore model;
    private final ConcurrentHashMap<UUID, AtomicInteger> running = new ConcurrentHashMap<>();

    ImportLimits(ImportProperties properties, ImportSessionRepository sessions, Clock clock) {
        this.properties = properties;
        this.sessions = sessions;
        this.clock = clock;
        this.model = new Semaphore(properties.modelConcurrency(), true);
    }

    void checkDaily(UUID userId) {
        if (sessions.createdSince(userId, clock.instant().minus(DAY)) >= properties.dailyLimit()) {
            throw new ImportException(HttpStatus.TOO_MANY_REQUESTS, "daily_limit",
                    "Не больше " + properties.dailyLimit() + " импортов в сутки, попробуйте завтра");
        }
    }

    /**
     * Занимает место пользователя на время импорта; вернуть через {@link Permit#close()}.
     * Счётчик в памяти: при нескольких экземплярах сервера предел действует на каждый.
     */
    Permit userSlot(UUID userId) {
        var counter = running.computeIfAbsent(userId, id -> new AtomicInteger());
        if (counter.incrementAndGet() > properties.concurrentPerUser()) {
            release(userId, counter);
            throw new ImportException(HttpStatus.TOO_MANY_REQUESTS, "too_many_concurrent",
                    "Дождитесь окончания предыдущего импорта");
        }
        return () -> release(userId, counter);
    }

    /** Место у модели; если провайдер занят дольше {@code modelWait} — понятная ошибка вместо очереди. */
    Permit modelSlot() {
        try {
            if (!model.tryAcquire(properties.modelWait().toMillis(), TimeUnit.MILLISECONDS)) {
                throw new ImportException(HttpStatus.SERVICE_UNAVAILABLE, "import_busy",
                        "Импорт сейчас загружен, попробуйте через минуту");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ImportException(HttpStatus.SERVICE_UNAVAILABLE, "import_busy", "Импорт прерван");
        }
        return model::release;
    }

    private void release(UUID userId, AtomicInteger counter) {
        if (counter.decrementAndGet() <= 0) {
            running.remove(userId, counter);
        }
    }

    /** Освобождение места; без проверяемых исключений. */
    @FunctionalInterface
    interface Permit extends AutoCloseable {
        @Override
        void close();
    }
}
