package com.sizylift.backend.voice;

import java.time.Duration;
import java.util.UUID;

import com.sizylift.backend.voice.config.VoiceConfiguration;
import com.sizylift.backend.voice.model.DeepSeekGapResolver;
import com.sizylift.backend.voice.model.GapResolver;
import com.sizylift.backend.voice.service.VoicePipeline;
import com.sizylift.backend.voice.service.VoiceTestCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** Настоящий DeepSeek (платный вызов) для непонятых кусков. Только при заданном DEEPSEEK_API_KEY. */
@EnabledIfEnvironmentVariable(named = DeepgramLiveTest.DEEPSEEK_KEY, matches = ".+")
class DeepSeekLiveTest {
    private static final UUID SEATED_PRESS = UUID.fromString("14000000-0000-4000-8000-000000000052");
    private static final UUID SCOTT = UUID.fromString("14000000-0000-4000-8000-000000000060");

    private final JsonMapper json = JsonMapper.builder().build();
    private final DeepSeekGapResolver resolver = new DeepSeekGapResolver(VoiceConfiguration.client(Duration.ofSeconds(10)),
            json, System.getenv(DeepgramLiveTest.DEEPSEEK_KEY), "https://api.deepseek.com", "deepseek-flash", 1024);

    @Test
    void resolvesNewExerciseAndNoteWithoutInventingNumbers() {
        var outcome = pipeline().fromText(UUID.randomUUID(), UUID.randomUUID(),
                "Жим арнольда четыре по десять по шестнадцать. Жим лёжа три по восемь на восемьдесят с паузой на груди.",
                VoiceTestCatalog.system());
        System.out.printf("DeepSeek: %d мс, %s, токены %d/%d%n", outcome.fallbackNanos() / 1_000_000, outcome.fallback(),
                outcome.inputTokens(), outcome.outputTokens());
        var items = outcome.response().items();
        assertThat(outcome.fallback()).isIn(VoicePipeline.Fallback.RESOLVED, VoicePipeline.Fallback.PARTIAL);
        assertThat(items.get(0).exerciseId()).isNull();
        assertThat(items.get(0).proposal().source()).isEqualTo("model");
        assertThat(items.get(0).proposal().name()).containsIgnoringCase("арнольд");
        assertThat(items.get(0).lines().getFirst().weightKg()).isEqualTo(16.0);
        assertThat(items.get(1).notes()).anySatisfy(n -> assertThat(n).containsIgnoringCase("пауз"));
        assertThat(items.get(1).lines()).hasSize(1);
    }

    /** Названия, искажённые распознаванием, модель сопоставляет с каталогом. */
    @Test
    void matchesMisrecognizedNamesToCatalog() {
        var items = pipeline().fromText(UUID.randomUUID(), UUID.randomUUID(),
                "Джимганделий сиди четыре по десять по двадцать четыре. Загибание на скамье скота три по десять на двадцать пять.",
                VoiceTestCatalog.system()).response().items();
        assertThat(items.get(0).exerciseId()).isEqualTo(SEATED_PRESS);
        assertThat(items.get(0).confidence()).isEqualTo("medium");
        assertThat(items.get(1).exerciseId()).isEqualTo(SCOTT);
    }

    @Test
    void wrongKeyMeansUnavailable() {
        var broken = new DeepSeekGapResolver(VoiceConfiguration.client(Duration.ofSeconds(10)), json, "invalid-key",
                "https://api.deepseek.com", "deepseek-flash", 1024);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> broken.resolve("ДЫРКИ\nG1\tвне упражнений\tну"))
                .isInstanceOf(GapResolver.Unavailable.class);
    }

    private VoicePipeline pipeline() {
        return new VoicePipeline((audio, type, keyterms) -> { throw new AssertionError("Звук не нужен"); }, resolver, json);
    }
}
