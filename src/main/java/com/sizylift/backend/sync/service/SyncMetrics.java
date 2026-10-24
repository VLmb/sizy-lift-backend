package com.sizylift.backend.sync.service;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import com.sizylift.backend.publication.service.AggregatePublisher;
import com.sizylift.backend.sync.dto.SyncPushResponse.ItemResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.stereotype.Component;

/** Метрики и трейсы sync (docs/observability.md). Теги — только тип и итог, без идентификаторов. */
@Component
class SyncMetrics {

    static final String PUSH_OBSERVATION = "sizylift.sync.push";
    static final String PUSH_ITEMS_METER = "sizylift.sync.push.items";
    /** Клиент прислал ревизию новее серверной: так бывает после восстановления базы из бэкапа. */
    static final String BASE_AHEAD_METER = "sizylift.sync.push.base.ahead";
    static final String UNKNOWN_TYPE = "unknown";

    private final MeterRegistry meters;
    private final ObservationRegistry observations;
    private final Set<String> knownTypes;

    SyncMetrics(MeterRegistry meters, ObservationRegistry observations, List<AggregatePublisher<?>> publishers) {
        this.meters = meters;
        this.observations = observations;
        this.knownTypes = Set.copyOf(publishers.stream().map(AggregatePublisher::type).toList());
    }

    <T> T observePush(Supplier<T> push) {
        return Observation.createNotStarted(PUSH_OBSERVATION, observations)
                .contextualName("sync push")
                .observe(push);
    }

    /** Вызывается только для известного типа: неизвестные отклоняются раньше. */
    void countBaseAhead(String type) {
        meters.counter(BASE_AHEAD_METER, "type", type).increment();
    }

    void count(ItemResult result) {
        meters.counter(PUSH_ITEMS_METER,
                "type", knownTypes.contains(result.type()) ? result.type() : UNKNOWN_TYPE,
                "status", result.status().code()).increment();
    }
}
