package com.sizylift.backend.imports.service;

import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class ImportMetrics {
    private final MeterRegistry meters;
    public ImportMetrics(MeterRegistry meters) { this.meters = meters; }
    public void outcome(String source, String outcome, long nanos) {
        meters.counter("sizylift.import.requests", "source", source, "outcome", outcome).increment();
        meters.timer("sizylift.import.duration", "source", source, "outcome", outcome).record(nanos, TimeUnit.NANOSECONDS);
    }
    public void usage(int input, int output, int cached) {
        meters.counter("sizylift.import.tokens", "direction", "input").increment(input);
        meters.counter("sizylift.import.tokens", "direction", "output").increment(output);
        meters.counter("sizylift.import.tokens", "direction", "cached").increment(cached);
    }
}
