package com.sizylift.backend.imports.model;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import com.sizylift.backend.imports.dto.ImportDraftV1;
import com.sizylift.backend.imports.entity.ImportLanguage;
import com.sizylift.backend.imports.entity.ImportSession.SourceItem;
import com.sizylift.backend.imports.template.ExerciseResolver;
import com.sizylift.backend.imports.template.ImportAssembly;
import com.sizylift.backend.template.TemplateVocabulary;
import com.sizylift.backend.template.TemplateVocabulary.Scope;
import com.sizylift.backend.voice.service.VoiceTestCatalog;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Сторож промпта импорта: ключи совпадают со словарём, а пример из промпта разбирается сервером без потерь. */
class ImportPromptTest {
    private static final Pattern KEY_IN_TEXT = Pattern.compile("([А-Яа-яЁёA-Za-z_]+)=");
    /** Ключи строк структуры: программа, упражнения, недели, дни, блоки. Ключи подходов — в словаре. */
    private static final Set<String> STRUCTURE = Set.of("описание", "тип", "или", "цикл", "название", "дни", "как", "заметка");
    private static final LocalDate MONDAY = LocalDate.of(2026, 11, 9);

    @Test
    void promptMentionsAllSetKeysAndNothingUnknown() {
        var rules = ImportPrompt.rules();
        assertThat(rules).doesNotContain("{{").doesNotContain("}}");
        var mentioned = new HashSet<String>();
        var m = KEY_IN_TEXT.matcher(rules);
        while (m.find()) mentioned.add(TemplateVocabulary.key(m.group(1)));
        var known = new HashSet<>(TemplateVocabulary.allKeys(Scope.IMPORT));
        known.addAll(STRUCTURE);
        assertThat(known).containsAll(mentioned);
        assertThat(mentioned).containsAll(TemplateVocabulary.allKeys(Scope.IMPORT));
    }

    @Test
    void exampleAnswerIsParsedWithoutWarnings() {
        var draft = parse(example()).snapshot(true);
        assertThat(draft.warnings()).isEmpty();
        assertThat(draft.programDraft().name()).isEqualTo("POWERBUILDING");
        assertThat(draft.programDraft().cycles()).extracting(ImportDraftV1.Cycle::name).containsExactly("Объём", "Разгрузка");
        assertThat(draft.plan()).extracting(ImportDraftV1.PlannedWeek::status).containsOnly(ImportDraftV1.DONE);

        var resolutions = draft.exerciseResolutions();
        assertThat(resolutions).hasSize(5);
        assertThat(resolutions.getFirst().status()).isEqualTo("matched");
        assertThat(resolutions.getFirst().originalName()).isEqualTo("Присед соревновательный");
        assertThat(resolutions.getLast().status()).isEqualTo("ambiguous");
        assertThat(resolutions.getLast().candidates()).hasSize(2);

        var week1 = draft.programDraft().cycles().getFirst().weeks().getFirst();
        assertThat(week1.workouts()).extracting(ImportDraftV1.Day::dayOffset).containsExactly(0, 1);
        assertThat(week1.workouts().getFirst().title()).isEqualTo("Ноги / жим");
        var squat = week1.workouts().getFirst().blocks().getFirst().exercises().getFirst();
        assertThat(squat.comment()).startsWith("соревновательный").contains("Без отказа");
        var set = squat.sets().getFirst();
        assertThat(List.of(set.count(), set.repetitions(), set.percentOneRm(), set.weightKg(), set.rpe(), set.setType()))
                .containsExactly("4", "6", "67", "105", "7", "working");
        var curl = week1.workouts().getFirst().blocks().get(2).exercises().getFirst();
        assertThat(curl.sets().getFirst().repetitions()).isEqualTo("10-12");
        var week2 = draft.programDraft().cycles().get(1).weeks().getFirst();
        assertThat(week2.workouts()).extracting(ImportDraftV1.Day::dayOffset).containsExactly(7, 8);
    }

    @Test
    void repeatedWeeksCopyTheirSourceAndNumberedDaysGoEveryOtherDay() {
        var draft = parse("""
                Программа: Тест
                Упражнения:
                - Присед → Присед со штангой | тип=вес_повторы
                План:
                Неделя: 1 | дни=1,2,3
                Неделя: 2-3 | как=1
                Тренировки:
                Неделя: 1
                День: 1
                Упражнение: Присед
                Подход: кол=3 | повт=5 | вес=100 кг
                День: 2
                Упражнение: Присед
                Подход: кол=3 | повт=5
                День: 3
                Упражнение: Присед
                Подход: кол=1 | повт=макс | тип=дроп
                """).snapshot(true);
        assertThat(draft.plan()).extracting(ImportDraftV1.PlannedWeek::status).containsOnly(ImportDraftV1.DONE);
        var weeks = draft.programDraft().cycles().getFirst().weeks();
        assertThat(weeks).extracting(ImportDraftV1.Week::number).containsExactly(1, 2, 3);
        assertThat(weeks.get(2).workouts()).extracting(ImportDraftV1.Day::dayOffset).containsExactly(14, 16, 18);
        var last = weeks.getFirst().workouts().get(2).blocks().getFirst().exercises().getFirst().sets().getFirst();
        assertThat(List.of(last.repetitions(), last.setType())).containsExactly("AMRAP", "drop");
    }

    @Test
    void aBrokenLineIsAWarningAndATruncatedWeekWaitsForAnotherCall() {
        var assembly = assembly();
        var cursor = assembly.cursor(null);
        for (var line : """
                Программа: Тест
                Упражнения:
                - Присед → Присед со штангой | тип=вес_повторы
                План:
                Неделя: 1 | дни=Пн
                Неделя: 2 | дни=Пн
                Тренировки:
                Неделя: 1
                День: Пн
                Упражнение: Присед
                Подход: 3х5 100
                Подход: кол=3 | повт=5 | вес=сто кг
                Неделя: 2
                День: Пн
                Упражнение: Присед""".split("\n")) cursor.line(line);
        assertThat(cursor.end(true)).containsExactly(1);
        assertThat(assembly.pendingWeeks()).containsExactly(2);
        var draft = assembly.snapshot(false);
        assertThat(draft.warnings()).hasSize(2);
        var set = draft.programDraft().cycles().getFirst().weeks().getFirst().workouts().getFirst().blocks().getFirst()
                .exercises().getFirst().sets();
        assertThat(set).hasSize(1);
        assertThat(List.of(set.getFirst().count(), set.getFirst().weightKg())).containsExactly("3", "");
    }

    @Test
    void workoutsWithoutTheirHeaderAreStillReadAsWorkouts() {
        var draft = parse("""
                Программа: Тест
                Упражнения:
                - Присед → Присед со штангой | тип=вес_повторы
                План:
                Неделя: 1 | цикл=Объём | дни=Пн
                Неделя 1
                День: Пн
                Упражнение: Присед
                Подход: кол=3 | повт=5
                """).snapshot(true);
        assertThat(draft.warnings()).isEmpty();
        assertThat(draft.plan().getFirst().cycle()).isEqualTo("Объём");
        assertThat(draft.plan().getFirst().status()).isEqualTo(ImportDraftV1.DONE);
    }

    @Test
    void aQuestionStopsTheParse() {
        var draft = parse("Вопрос: Какой лист разбирать — новичок или опытный?").snapshot(true);
        assertThat(draft.needsClarification()).isTrue();
        assertThat(draft.clarificationMessage()).startsWith("Какой лист");
    }

    private static String example() {
        var rules = ImportPrompt.rules();
        return rules.substring(rules.indexOf("Ответ:\n") + "Ответ:\n".length(), rules.indexOf("\n\nКАТАЛОГ"));
    }

    private static ImportAssembly assembly() {
        var source = List.of(new SourceItem("sheet:1!C6", "Присед соревновательный"));
        return new ImportAssembly(new ExerciseResolver(VoiceTestCatalog.system(), source), MONDAY, ImportLanguage.RUSSIAN);
    }

    private static ImportAssembly parse(String text) {
        var assembly = assembly();
        var cursor = assembly.cursor(null);
        for (var line : text.split("\n")) cursor.line(line);
        cursor.end(false);
        return assembly;
    }
}
