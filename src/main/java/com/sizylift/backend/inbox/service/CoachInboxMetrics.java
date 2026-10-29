package com.sizylift.backend.inbox.service;

import java.util.function.Supplier;

import com.sizylift.backend.inbox.entity.CoachEventKind;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/** Метрики ежедневной задачи «Входящих» (docs/observability.md); теги — тип события и итог запуска. */
@Component
class CoachInboxMetrics {

    static final String DAILY_EVENTS_METER = "sizylift.coach.inbox.daily.events";
    static final String DAILY_RUN_METER = "sizylift.coach.inbox.daily.run";

    /** Итог запуска: выполнен, пропущен (работает другой экземпляр) или упал. */
    enum Outcome {
        COMPLETED("completed"),
        SKIPPED("skipped"),
        FAILED("failed");

        private final String code;

        Outcome(String code) {
            this.code = code;
        }
    }

    private final MeterRegistry meters;

    CoachInboxMetrics(MeterRegistry meters) {
        this.meters = meters;
    }

    /** Длительность запуска с итогом; {@code null} от задачи — запуск пропущен. */
    <T> T timeRun(Supplier<T> run) {
        var sample = Timer.start(meters);
        var outcome = Outcome.FAILED;
        try {
            var result = run.get();
            outcome = result == null ? Outcome.SKIPPED : Outcome.COMPLETED;
            return result;
        } finally {
            sample.stop(Timer.builder(DAILY_RUN_METER)
                    .description("Coach inbox daily job run")
                    .tag("outcome", outcome.code)
                    .register(meters));
        }
    }

    void countCreated(CoachEventKind kind, int created) {
        meters.counter(DAILY_EVENTS_METER, "kind", kind.code()).increment(created);
    }
}
