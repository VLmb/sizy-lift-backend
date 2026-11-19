package com.sizylift.backend.voice.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.sizylift.backend.voice.dto.VoiceDtos.Line;
import com.sizylift.backend.voice.model.GapResolver;
import com.sizylift.backend.voice.parser.WorkoutTextParser;
import com.sizylift.backend.voice.model.SpeechRecognizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** Пайплайн без сети: распознавание и модель подменены. */
class VoicePipelineTest {
    private static final UUID REQUEST = UUID.fromString("70000000-0000-4000-8000-000000000001");
    private static final UUID OWNER = UUID.fromString("70000000-0000-4000-8000-000000000002");
    private static final UUID SQUAT = UUID.fromString("14000000-0000-4000-8000-000000000001");
    private static final UUID SEATED_PRESS = UUID.fromString("14000000-0000-4000-8000-000000000052");

    private final List<String> inputs = new ArrayList<>();
    private final AtomicReference<String> answer = new AtomicReference<>("{\"gaps\":[]}");
    private final GapResolver resolver = input -> {
        inputs.add(input);
        if (answer.get() == null) throw new GapResolver.Unavailable();
        return new GapResolver.Result(answer.get(), 100, 10);
    };

    private VoicePipeline pipeline(String transcript) {
        SpeechRecognizer recognizer = (audio, type, keyterms) -> {
            assertThat(keyterms).contains("Присед", "Жим лёжа");
            return transcript;
        };
        return new VoicePipeline(recognizer, resolver, JsonMapper.builder().build());
    }

    @Test
    void understoodPhraseDoesNotCallModel() {
        var outcome = pipeline("Присед три по пять на сто, отдых две минуты").run(REQUEST, OWNER, new byte[] {1}, "audio/ogg",
                VoiceTestCatalog.system());
        var item = outcome.response().items().getFirst();
        assertThat(item.exerciseId()).isEqualTo(SQUAT);
        assertThat(item.lines().getFirst().count()).isEqualTo(3);
        assertThat(item.lines().getFirst().weightKg()).isEqualTo(100.0);
        assertThat(item.restSeconds()).isEqualTo(120);
        assertThat(outcome.fallback()).isEqualTo(VoicePipeline.Fallback.SKIPPED);
        assertThat(inputs).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "три по восемь-двенадцать",
            "три по восемь двенадцать повторений",
            "три подхода по восемь тире двенадцать",
            "три подхода по восемь дефис двенадцать",
            "три подхода от восьми до двенадцати повторов",
            "три подхода с восьми до двенадцати раз",
            "три подхода между восемью и двенадцатью повторениями",
            "3 подхода повторов от 8 до 12",
            "3 подхода по 8 до 12 повторов",
            "3 × 8—12"
    })
    void understandsDifferentWaysToDictateTheSameRangeWithoutFallback(String parameters) {
        var outcome = pipeline("Присед " + parameters + " с весом 60 кг, отдых 90 секунд")
                .run(REQUEST, OWNER, new byte[] {1}, "audio/ogg", VoiceTestCatalog.system());
        assertThat(outcome.response().items()).singleElement().satisfies(item -> {
            assertThat(item.exerciseId()).isEqualTo(SQUAT);
            assertThat(item.lines()).singleElement().satisfies(line ->
                    assertThat(line).extracting(Line::count, Line::reps, Line::repsMax, Line::weightKg)
                            .containsExactly(3, 8, 12, 60.0));
            assertThat(item.restSeconds()).isEqualTo(90);
        });
        assertThat(outcome.fallback()).isEqualTo(VoicePipeline.Fallback.SKIPPED);
        assertThat(inputs).isEmpty();
    }

    @Test
    void keepsRangesTogetherWithLoadAndClearsAnOverriddenUpperBound() {
        var outcome = pipeline("Присед 100 на 8–20, потом 70 процентов на 8–12. Жим лежа 3 по 8–12, последний 10 повторов.")
                .run(REQUEST, OWNER, new byte[] {1}, "audio/ogg", VoiceTestCatalog.system());
        assertThat(outcome.response().items()).hasSize(2);
        var squat = outcome.response().items().getFirst();
        assertThat(squat.lines()).hasSize(2);
        assertThat(squat.lines().get(0)).extracting(Line::count, Line::reps, Line::repsMax, Line::weightKg)
                .containsExactly(1, 8, 20, 100.0);
        assertThat(squat.lines().get(1)).extracting(Line::count, Line::reps, Line::repsMax, Line::percent)
                .containsExactly(1, 8, 12, 70.0);
        var bench = outcome.response().items().getLast();
        assertThat(bench.lines()).extracting(Line::count).containsExactly(2, 1);
        assertThat(bench.lines()).extracting(Line::reps).containsExactly(8, 10);
        assertThat(bench.lines()).extracting(Line::repsMax).containsExactly(12, null);
        assertThat(inputs).isEmpty();
    }

    @Test
    void aLastSetToFailureDoesNotKeepTheCopiedRange() {
        var outcome = pipeline("Присед три по 8–12, последний до отказа")
                .run(REQUEST, OWNER, new byte[] {1}, "audio/ogg", VoiceTestCatalog.system());
        var lines = outcome.response().items().getFirst().lines();
        assertThat(lines).extracting(Line::count).containsExactly(2, 1);
        assertThat(lines).extracting(Line::reps).containsExactly(8, null);
        assertThat(lines).extracting(Line::repsMax).containsExactly(12, null);
        assertThat(lines).extracting(Line::toFailure).containsExactly(false, true);
    }

    @Test
    void numbersInSeparateSetsOtherUnitsAndInvalidBoundsAreNotRepetitionRanges() {
        var outcome = pipeline("Присед 3 по 8, потом 2 по 12. Жим лёжа 3 по 8, 12 кг. "
                + "Отжимания 3 по 12–8. Подтягивания 3 по 8–12 кг. Планка 3 по 30 секунд.")
                .run(REQUEST, OWNER, new byte[] {1}, "audio/ogg", VoiceTestCatalog.system());
        var items = outcome.response().items();
        assertThat(items).hasSize(5);
        assertThat(items.get(0).lines()).extracting(Line::reps).containsExactly(8, 12);
        assertThat(items.get(1).lines()).singleElement().satisfies(line ->
                assertThat(line).extracting(Line::reps, Line::weightKg).containsExactly(8, 12.0));
        assertThat(items.get(2).lines()).singleElement().satisfies(line -> assertThat(line.reps()).isNull());
        assertThat(items.get(3).lines()).singleElement().satisfies(line -> assertThat(line.reps()).isNull());
        assertThat(items.get(4).lines()).singleElement().satisfies(line -> assertThat(line.durationSeconds()).isEqualTo(30));
        assertThat(items.stream().flatMap(item -> item.lines().stream())).allMatch(line -> line.repsMax() == null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"от восьми до двенадцати раз", "8 тире 12", "8 12 повторов", "8, потом 12", "8 или 12", "8–12 кг", "12–8"})
    void fallbackChecksThatBothNumbersFormOneRepetitionRange(String fragment) {
        var catalog = VoiceTestCatalog.system();
        var draft = VoiceDraft.of(new WorkoutTextParser(catalog.dictionary()).parse("Присед"));
        var item = draft.items.getFirst();
        item.unrecognized.add(fragment);
        var gaps = GapMerger.gaps(draft);
        int resolved = GapMerger.apply(JsonMapper.builder().build(),
                """
                {"gaps":[{"gap":"G1","kind":"sets","sets":["r=8-12"]}]}
                """, gaps, draft, catalog);
        boolean expected = fragment.equals("от восьми до двенадцати раз") || fragment.equals("8 тире 12")
                || fragment.equals("8 12 повторов");
        assertThat(resolved).isEqualTo(expected ? 1 : 0);
        if (expected) {
            assertThat(item.lines).singleElement().satisfies(line ->
                    assertThat(line.repsMax()).isEqualTo(12));
        } else {
            assertThat(item.lines).isEmpty();
            assertThat(item.unrecognized).containsExactly(fragment);
        }
    }

    @Test
    void checkedModelAnswersAreApplied() {
        answer.set("""
                {"gaps":[
                 {"gap":"G1","kind":"exercise","match":"c52","newName":"","resultType":"","note":"","sets":[]},
                 {"gap":"G2","kind":"note","match":"","newName":"","resultType":"","note":"пауза внизу","sets":[]}
                ]}""");
        var outcome = pipeline("Жим гантелек сидячи четыре по десять по двадцать четыре. Присед три по пять на сто с паузой внизу")
                .run(REQUEST, OWNER, new byte[] {1}, "audio/ogg", VoiceTestCatalog.system());
        var items = outcome.response().items();
        assertThat(inputs.getFirst()).contains("КАТАЛОГ", "ДЫРКИ", "G1\tназвание E1", "G2\tуточнение к E2\tс паузой внизу");
        assertThat(items.get(0).exerciseId()).isEqualTo(SEATED_PRESS);
        assertThat(items.get(0).confidence()).isEqualTo("medium");
        assertThat(items.get(0).lines().getFirst().weightKg()).isEqualTo(24.0);
        assertThat(items.get(1).notes()).containsExactly("пауза внизу");
        assertThat(items.get(1).unrecognized()).isEmpty();
        assertThat(outcome.fallback()).isEqualTo(VoicePipeline.Fallback.RESOLVED);
    }

    @Test
    void inventedNumbersAndNamesAreRejected() {
        answer.set("""
                {"gaps":[
                 {"gap":"G1","kind":"exercise","match":"","newName":"Жим штанги стоя","resultType":"weight_reps","note":"","sets":[]},
                 {"gap":"G2","kind":"sets","match":"","newName":"","resultType":"","note":"","sets":["n=3 r=10 kg=85"]}
                ]}""");
        var outcome = pipeline("Жим арнольда четыре по десять по шестнадцать. Присед три по пять на сто с цепями")
                .run(REQUEST, OWNER, new byte[] {1}, "audio/ogg", VoiceTestCatalog.system());
        var items = outcome.response().items();
        // «Жим штанги стоя» — не из слов речи: остаётся предложение по сказанному.
        assertThat(items.get(0).proposal().source()).isEqualTo("spoken");
        assertThat(items.get(0).proposal().name()).isEqualTo("Жим арнольда");
        // 85 кг в речи нет: подходы модели не добавлены, кусок остался непонятым.
        assertThat(items.get(1).lines()).hasSize(1);
        assertThat(items.get(1).unrecognized()).isNotEmpty();
        assertThat(outcome.fallback()).isEqualTo(VoicePipeline.Fallback.UNRESOLVED);
    }

    @Test
    void modelProposalForNewExerciseKeepsSets() {
        answer.set("""
                {"gaps":[{"gap":"G1","kind":"exercise","match":"","newName":"Жим Арнольда","resultType":"weight_reps","note":"","sets":[]}]}""");
        var item = pipeline("Жим арнольда четыре по десять по шестнадцать")
                .run(REQUEST, OWNER, new byte[] {1}, "audio/ogg", VoiceTestCatalog.system()).response().items().getFirst();
        assertThat(item.exerciseId()).isNull();
        assertThat(item.proposal().name()).isEqualTo("Жим Арнольда");
        assertThat(item.proposal().resultType()).isEqualTo("weight_reps");
        assertThat(item.proposal().source()).isEqualTo("model");
        assertThat(item.lines().getFirst().weightKg()).isEqualTo(16.0);
        assertThat(item.candidates()).isNotEmpty();
    }

    @Test
    void modelFailureKeepsWhatWasUnderstood() {
        answer.set(null);
        var outcome = pipeline("Присед три по пять на сто, потом жим арнольда четыре по десять")
                .run(REQUEST, OWNER, new byte[] {1}, "audio/ogg", VoiceTestCatalog.system());
        assertThat(outcome.fallback()).isEqualTo(VoicePipeline.Fallback.FAILED);
        assertThat(outcome.response().items()).hasSize(2);
        assertThat(outcome.response().items().get(1).exerciseId()).isNull();
        assertThat(outcome.response().items().get(1).lines().getFirst().reps()).isEqualTo(10);
    }

    @Test
    void silenceIsNotAnError() {
        var outcome = pipeline("").run(REQUEST, OWNER, new byte[] {1}, "audio/ogg", VoiceTestCatalog.system());
        assertThat(outcome.response().speechDetected()).isFalse();
        assertThat(outcome.response().items()).isEmpty();
        assertThat(inputs).isEmpty();
    }

    @Test
    void personalAliasWinsAndCustomNamesComeFirstInHints() {
        var custom = UUID.randomUUID();
        var sources = new ArrayList<>(VoiceTestCatalog.systemSources());
        sources.add(new VoiceCatalog.Source(custom, true, "Жим Арнольда", null, "weight_reps"));
        var catalog = VoiceCatalog.build(sources, VoiceTestCatalog.systemAliases(),
                List.of(new VoiceCatalog.UserAlias(SEATED_PRESS, "шраги")), 300);
        assertThat(catalog.keyterms().subList(0, 2)).containsExactly("Жим Арнольда", "шраги");
        assertThat(catalog.keyterms().stream().mapToInt(String::length).sum()).isLessThanOrEqualTo(300);
        var items = pipeline("Жим арнольда три по десять, шраги три по двенадцать")
                .fromText(REQUEST, OWNER, "Жим арнольда три по десять, шраги три по двенадцать", catalog).response().items();
        assertThat(items.get(0).exerciseId()).isEqualTo(custom);
        assertThat(items.get(1).exerciseId()).isEqualTo(SEATED_PRESS);
        assertThat(items.get(1).candidates()).extracting(c -> c.name()).contains("Шраги с гантелями");
    }
}
