package com.sizylift.backend.imports.service;

import java.util.function.Supplier;

import com.sizylift.backend.imports.entity.ImportSource;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.stereotype.Component;

/** Метрики импорта (ADR-0008, раздел 4); теги — источник и итог, без идентификаторов и текста. */
@Component
class ImportMetrics {

    static final String REQUESTS_METER = "sizylift.import.requests";
    static final String TOKENS_METER = "sizylift.import.tokens";
    static final String DURATION_OBSERVATION = "sizylift.import.duration";
    static final String ROUNDS_METER = "sizylift.import.rounds";
    static final String DIRECTION_IN = "in";
    static final String DIRECTION_OUT = "out";
    /** Ответы на вопросы: источник уже не важен. */
    static final String ANSWERS_SOURCE = "answers";

    private final MeterRegistry meters;
    private final ObservationRegistry observations;
    private final DistributionSummary rounds;

    ImportMetrics(MeterRegistry meters, ObservationRegistry observations) {
        this.meters = meters;
        this.observations = observations;
        this.rounds = DistributionSummary.builder(ROUNDS_METER)
                .description("Question rounds before the draft")
                .register(meters);
    }

    <T> T observe(Supplier<T> work) {
        return Observation.createNotStarted(DURATION_OBSERVATION, observations)
                .contextualName("program import")
                .observe(work);
    }

    void request(ImportSource source, String outcome) {
        request(source.code(), outcome);
    }

    void request(String source, String outcome) {
        meters.counter(REQUESTS_METER, "source", source, "outcome", outcome).increment();
    }

    void tokens(int in, int out) {
        meters.counter(TOKENS_METER, "direction", DIRECTION_IN).increment(in);
        meters.counter(TOKENS_METER, "direction", DIRECTION_OUT).increment(out);
    }

    void draftAfterRounds(int questionRounds) {
        rounds.record(questionRounds);
    }
}
