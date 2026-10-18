package com.sizylift.backend.publication.service;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import com.sizylift.backend.publication.dto.PublicationBatchResponse.ItemResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.stereotype.Component;

/** Метрики и трейсы публикации (docs/observability.md). */
@Component
class PublicationMetrics {

    static final String BATCH_OBSERVATION = "sizylift.publication.batch";
    static final String BATCH_SIZE_METER = "sizylift.publication.batch.size";
    static final String ITEMS_METER = "sizylift.publication.items";
    static final String UNKNOWN_TYPE = "unknown";
    static final String NO_ERROR = "none";

    private final MeterRegistry meters;
    private final ObservationRegistry observations;
    private final DistributionSummary batchSize;
    private final Set<String> knownTypes;

    PublicationMetrics(MeterRegistry meters, ObservationRegistry observations, List<AggregatePublisher<?>> publishers) {
        this.meters = meters;
        this.observations = observations;
        this.knownTypes = Set.copyOf(publishers.stream().map(AggregatePublisher::type).toList());
        this.batchSize = DistributionSummary.builder(BATCH_SIZE_METER)
                .description("Aggregates per publication batch")
                .baseUnit("aggregates")
                .register(meters);
    }

    /** Таймер и span вокруг всей пачки. */
    <T> T observeBatch(int size, Supplier<T> batch) {
        batchSize.record(size);
        return Observation.createNotStarted(BATCH_OBSERVATION, observations)
                .contextualName("publication batch")
                .observe(batch);
    }

    /** Теги низкой кардинальности: неизвестный тип из запроса не попадает в тег как есть. */
    void count(ItemResult result) {
        Counter.builder(ITEMS_METER)
                .description("Published aggregates by type and outcome")
                .tag("type", knownTypes.contains(result.type()) ? result.type() : UNKNOWN_TYPE)
                .tag("status", result.status().code())
                .tag("error", result.error() == null ? NO_ERROR : result.error().code())
                .register(meters)
                .increment();
    }
}
