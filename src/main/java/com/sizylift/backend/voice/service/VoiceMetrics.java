package com.sizylift.backend.voice.service;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Метрики голосового ввода; теги — только исход и этап, без пользовательских данных. */
@Component
public class VoiceMetrics {
    private final MeterRegistry meters;

    public VoiceMetrics(MeterRegistry meters) { this.meters = meters; }

    public void outcome(String outcome) { meters.counter("sizylift.voice.requests", "outcome", outcome).increment(); }

    public void stage(String stage, long nanos) {
        meters.timer("sizylift.voice.duration", "stage", stage).record(nanos, TimeUnit.NANOSECONDS);
    }

    public void fallback(VoicePipeline.Fallback fallback, int input, int output) {
        meters.counter("sizylift.voice.fallback", "outcome", fallback.name().toLowerCase(Locale.ROOT)).increment();
        meters.counter("sizylift.voice.tokens", "direction", "input").increment(input);
        meters.counter("sizylift.voice.tokens", "direction", "output").increment(output);
    }

    public void audio(int bytes) { meters.summary("sizylift.voice.audio.bytes").record(bytes); }
}
