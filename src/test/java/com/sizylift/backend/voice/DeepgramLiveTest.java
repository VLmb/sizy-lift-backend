package com.sizylift.backend.voice;

import java.io.IOException;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

import com.sizylift.backend.voice.config.VoiceConfiguration;
import com.sizylift.backend.voice.model.DeepSeekGapResolver;
import com.sizylift.backend.voice.model.DeepgramSpeechRecognizer;
import com.sizylift.backend.voice.service.VoicePipeline;
import com.sizylift.backend.voice.service.VoiceTestCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Настоящий Deepgram (платный вызов). Запускается, только если задан DEEPGRAM_API_KEY; в CI без ключа пропускается.
 * Звук — синтезированная фраза, не голос пользователя.
 */
@EnabledIfEnvironmentVariable(named = DeepgramLiveTest.DEEPGRAM_KEY, matches = ".+")
class DeepgramLiveTest {
    static final String DEEPGRAM_KEY = "DEEPGRAM_API_KEY";
    static final String DEEPSEEK_KEY = "DEEPSEEK_API_KEY";
    private static final String AUDIO = "/voice/live-dictation.ogg";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final UUID SQUAT = UUID.fromString("14000000-0000-4000-8000-000000000001");
    private static final UUID BENCH = UUID.fromString("14000000-0000-4000-8000-000000000002");

    private final JsonMapper json = JsonMapper.builder().build();
    private final DeepgramSpeechRecognizer deepgram = new DeepgramSpeechRecognizer(VoiceConfiguration.client(TIMEOUT), json,
            System.getenv(DEEPGRAM_KEY), "https://api.deepgram.com", "nova-3", "ru");

    @Test
    void recognizesRussianDictationWithExerciseHints() throws IOException {
        long start = System.nanoTime();
        var text = deepgram.transcribe(audio(), "audio/ogg", VoiceTestCatalog.system().keyterms()).toLowerCase(Locale.ROOT);
        System.out.printf("Deepgram: %d мс%n", (System.nanoTime() - start) / 1_000_000);
        assertThat(text).contains("присед", "жим", "арнольд");
        assertThat(text).containsAnyOf("100", "сто");
    }

    @Test
    void wrongKeyIsRecognitionFailureWithoutProviderDetails() throws IOException {
        var broken = new DeepgramSpeechRecognizer(VoiceConfiguration.client(TIMEOUT), json, "invalid-key",
                "https://api.deepgram.com", "nova-3", "ru");
        var failure = org.assertj.core.api.Assertions.catchThrowableOfType(
                com.sizylift.backend.voice.service.VoiceFailure.class, () -> broken.transcribe(audio(), "audio/ogg", java.util.List.of()));
        assertThat(failure.code()).isEqualTo("recognition_failed");
    }

    /** Полный путь: звук → Deepgram → разбор → DeepSeek для упражнения не из каталога и уточнения. */
    @Test
    @EnabledIfEnvironmentVariable(named = DEEPSEEK_KEY, matches = ".+")
    void fullPipelineFromAudioUsesModelOnlyForGaps() throws IOException {
        var resolver = new DeepSeekGapResolver(VoiceConfiguration.client(Duration.ofSeconds(10)), json,
                System.getenv(DEEPSEEK_KEY), "https://api.deepseek.com", "deepseek-flash", 1024);
        var pipeline = new VoicePipeline(deepgram, resolver, json);
        var outcome = pipeline.run(UUID.randomUUID(), UUID.randomUUID(), audio(), "audio/ogg", VoiceTestCatalog.system());
        System.out.printf("Распознавание %d мс, разбор %d мкс, модель %d мс (%s)%n", outcome.recognitionNanos() / 1_000_000,
                outcome.parseNanos() / 1_000, outcome.fallbackNanos() / 1_000_000, outcome.fallback());
        var items = outcome.response().items();
        assertThat(items).hasSize(3);
        assertThat(items.get(0).exerciseId()).isEqualTo(SQUAT);
        assertThat(items.get(0).lines().getFirst().weightKg()).isEqualTo(100.0);
        assertThat(items.get(0).restSeconds()).isEqualTo(120);
        assertThat(items.get(1).exerciseId()).isNull();
        assertThat(items.get(1).proposal().name()).containsIgnoringCase("арнольд");
        assertThat(items.get(1).lines().getFirst().weightKg()).isEqualTo(16.0);
        assertThat(items.get(2).exerciseId()).isEqualTo(BENCH);
        assertThat(items.get(2).lines().getFirst().weightKg()).isEqualTo(80.0);
        assertThat(outcome.fallback()).isIn(VoicePipeline.Fallback.RESOLVED, VoicePipeline.Fallback.PARTIAL,
                VoicePipeline.Fallback.SKIPPED);
    }

    private static byte[] audio() throws IOException {
        try (var in = DeepgramLiveTest.class.getResourceAsStream(AUDIO)) {
            return in.readAllBytes();
        }
    }
}
