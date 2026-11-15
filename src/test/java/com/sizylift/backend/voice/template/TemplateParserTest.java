package com.sizylift.backend.voice.template;

import java.util.List;

import com.sizylift.backend.voice.dto.VoiceDtos.Item;
import com.sizylift.backend.voice.dto.VoiceDtos.Line;
import com.sizylift.backend.voice.service.VoiceCatalog;
import com.sizylift.backend.voice.service.VoiceTestCatalog;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Строгий разборщик шаблона: каждое правило docs/voice.md, «Разбор шаблоном». */
class TemplateParserTest {
    private static final VoiceCatalog CATALOG = VoiceTestCatalog.system();

    private static TemplateParser.Result parse(String... lines) {
        return TemplateParser.parse(String.join("\n", lines), CATALOG);
    }

    private static Item only(String... lines) {
        var items = parse(lines).items();
        assertThat(items).hasSize(1);
        return items.getFirst();
    }

    @Test
    void readsAllKeysOfWeightAndRepsSet() {
        var item = only("Упражнение: Присед со штангой | тип=вес_повторы",
                "Подход: кол=1 | повт=10 | вес=20 кг | тип=разминка | отдых=2 мин",
                "Подход: кол=4 | повт=6-8 | вес=102.5 кг | RPE=8 | отдых=90 с",
                "Подход: кол=1 | процент=70 | тип=топсет",
                "Подход: кол=2 | повт=12 | вес=80,5 кг | тип=бэкофф");
        assertThat(item.exerciseName()).isEqualTo("Присед со штангой");
        assertThat(item.confidence()).isEqualTo("high");
        var lines = item.lines();
        assertThat(lines.get(0)).extracting(Line::count, Line::reps, Line::weightKg, Line::type, Line::restSeconds)
                .containsExactly(1, 10, 20.0, "warmup", 120);
        assertThat(lines.get(1)).extracting(Line::count, Line::reps, Line::repsMax, Line::weightKg, Line::rpe, Line::type,
                Line::restSeconds).containsExactly(4, 6, 8, 102.5, 8.0, null, 90);
        assertThat(lines.get(2)).extracting(Line::percent, Line::type).containsExactly(70.0, "top");
        assertThat(lines.get(3)).extracting(Line::weightKg, Line::type).containsExactly(80.5, "backoff");
        assertThat(item.restSeconds()).isNull();
    }

    @Test
    void exerciseRestIsSetOnlyWhenAllLinesAgree() {
        var item = only("Упражнение: Жим штанги лёжа | тип=вес_повторы",
                "Подход: кол=3 | повт=8 | вес=60 кг | отдых=1.5 мин",
                "Подход: кол=1 | RPE=10 | вес=60 кг | отдых=1.5 мин");
        assertThat(item.restSeconds()).isEqualTo(90);
        assertThat(item.lines()).extracting(Line::restSeconds).containsExactly(90, 90);
    }

    @Test
    void noteMayStandAnywhereAndKeepPipesAndEquals() {
        var line = only("Упражнение: Присед со штангой | тип=вес_повторы",
                "Подход: кол=1 | повт=20 | заметка=пустой гриф | x=1 | тип=разминка | отдых=3 мин").lines().getFirst();
        assertThat(line.note()).isEqualTo("пустой гриф | x=1");
        assertThat(line.type()).isEqualTo("warmup");
        assertThat(line.restSeconds()).isEqualTo(180);
    }

    @Test
    void noRestIsZero() {
        var line = only("Упражнение: Гиперэкстензия | тип=доп_вес_повторы",
                "Подход: кол=3 | повт=15 | доп_вес=10 кг | отдых=0 с").lines().getFirst();
        assertThat(line.restSeconds()).isZero();
        assertThat(line.weightKg()).isEqualTo(10.0);
    }

    @Test
    void timeAndDistanceAreConverted() {
        var items = parse("Упражнение: Эллипс | тип=дистанция_время",
                "Подход: кол=1 | дистанция=2 км | время=10 мин | тип=разминка",
                "Упражнение: Планка на предплечьях | тип=время",
                "Подход: кол=3 | время=40 с").items();
        assertThat(items.get(0).lines().getFirst()).extracting(Line::distanceMeters, Line::durationSeconds, Line::type)
                .containsExactly(2000, 600, "warmup");
        assertThat(items.get(1).lines().getFirst()).extracting(Line::count, Line::durationSeconds).containsExactly(3, 40);
    }

    /** Тип упражнения — из каталога, а не из слов модели: вес у планки не пишется. */
    @Test
    void keysOfOtherTypeGoToIssues() {
        var item = only("Упражнение: Планка на предплечьях | тип=вес_повторы",
                "Подход: кол=2 | время=1 мин | вес=10 кг");
        assertThat(item.lines().getFirst().weightKg()).isNull();
        assertThat(item.lines().getFirst().durationSeconds()).isEqualTo(60);
        assertThat(item.issues()).singleElement().asString().contains("вес=10 кг");
    }

    @Test
    void malformedValuesAndLinesAreSkippedNotGuessed() {
        var result = parse("Упражнение: Присед со штангой | тип=вес_повторы",
                "Подход: кол=три | повт=10 | вес=много | отдых=без отдыха | неизвестно=1 | мусор",
                "просто текст");
        var line = result.items().getFirst().lines().getFirst();
        assertThat(line.count()).isNull();
        assertThat(line.reps()).isEqualTo(10);
        assertThat(line.weightKg()).isNull();
        assertThat(line.restSeconds()).isNull();
        assertThat(result.skipped()).contains("Присед со штангой: кол=три", "Присед со штангой: вес=много",
                "Присед со штангой: отдых=без отдыха", "Присед со штангой: «неизвестно=1»", "Присед со штангой: «мусор»",
                "строка: просто текст");
    }

    @Test
    void outOfRangeValuesAreDropped() {
        var line = only("Упражнение: Присед со штангой | тип=вес_повторы",
                "Подход: кол=40 | повт=10 | вес=5000 кг | RPE=12").lines().getFirst();
        assertThat(line.count()).isNull();
        assertThat(line.weightKg()).isNull();
        assertThat(line.rpe()).isNull();
        assertThat(line.reps()).isEqualTo(10);
    }

    @Test
    void catalogNameIsFoundExactlyThenByVariants() {
        var items = parse("Упражнение: присед СО штангой | тип=вес_повторы", "Подход: кол=3 | повт=5",
                "Упражнение: Румынка | тип=вес_повторы", "Подход: кол=3 | повт=8").items();
        assertThat(items).extracting(Item::exerciseName).containsExactly("Присед со штангой", "Румынская тяга");
        assertThat(items.getFirst().confidence()).isEqualTo("high");
    }

    @Test
    void unknownExerciseIsProposedWithModelType() {
        var item = only("Упражнение: Рывок гири | тип=вес_повторы | новое", "Подход: кол=1 | повт=20 | вес=16 кг");
        assertThat(item.exerciseId()).isNull();
        assertThat(item.confidence()).isEqualTo("low");
        assertThat(item.proposal()).extracting(p -> p.name(), p -> p.resultType(), p -> p.source())
                .containsExactly("Рывок гири", "weight_reps", "model");
        assertThat(item.lines().getFirst().weightKg()).isEqualTo(16.0);
    }

    @Test
    void commentsJoinIntoOne() {
        var item = only("Упражнение: Присед со штангой | тип=вес_повторы", "Подход: кол=3 | повт=5",
                "Комментарий: спину прямо", "Комментарий: колени наружу");
        assertThat(item.comment()).isEqualTo("спину прямо; колени наружу");
    }

    /** «Потом пойдём на жим» — упражнение без значений и без комментария в тренировку не попадает. */
    @Test
    void emptyExerciseIsDropped() {
        var result = parse("Упражнение: Присед со штангой | тип=вес_повторы", "Подход: кол=3 | повт=5",
                "Упражнение: Жим штанги лёжа | тип=вес_повторы", "Подход: кол=1",
                "Упражнение: Становая тяга классическая | тип=вес_повторы");
        assertThat(result.items()).extracting(Item::exerciseName).containsExactly("Присед со штангой");
        assertThat(result.skipped()).contains("пустое упражнение: Жим штанги лёжа",
                "пустое упражнение: Становая тяга классическая");
    }

    @Test
    void exerciseWithOnlyCountOrCommentIsKept() {
        var items = parse("Упражнение: Подтягивания | тип=вес_повторы", "Подход: кол=4",
                "Упражнение: Суставная разминка | тип=свободное", "Подход: кол=1", "Комментарий: всё тело").items();
        assertThat(items).extracting(Item::exerciseName).containsExactly("Подтягивания", "Суставная разминка");
    }

    @Test
    void setLineBeforeExerciseIsSkipped() {
        var result = parse("Подход: кол=3 | повт=5");
        assertThat(result.items()).isEmpty();
        assertThat(result.skipped()).containsExactly("строка: Подход: кол=3 | повт=5");
    }

    @Test
    void emptyTemplateGivesNothing() {
        assertThat(TemplateParser.parse("", CATALOG).items()).isEqualTo(List.of());
    }
}
