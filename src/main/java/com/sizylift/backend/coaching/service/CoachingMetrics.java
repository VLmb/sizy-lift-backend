package com.sizylift.backend.coaching.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** События связей (docs/observability.md); теги без идентификаторов. */
@Component
class CoachingMetrics {

    static final String EVENTS_METER = "sizylift.coaching.events";

    enum Event {
        INVITE_CREATED("invite_created"),
        LINK_CREATED("link_created"),
        LINK_ENDED("link_ended"),
        HEALTH_GRANTED("health_granted"),
        HEALTH_REVOKED("health_revoked");

        private final String code;

        Event(String code) {
            this.code = code;
        }
    }

    private final MeterRegistry meters;

    CoachingMetrics(MeterRegistry meters) {
        this.meters = meters;
    }

    void count(Event event) {
        meters.counter(EVENTS_METER, "event", event.code).increment();
    }
}
