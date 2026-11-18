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
import com.sizylift.backend.imports.template.ProgramLine;
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
    void promptsMentionOnlyKnownKeysAndTheWeeksPromptUsesRpeForEffort() {
        for (var prompt : List.of(ImportPrompt.UNDERSTAND, ImportPrompt.MATCH, ImportPrompt.WEEKS)) {
            assertThat(prompt).doesNotContain("{{").doesNotContain("}}");
            var known = new HashSet<>(TemplateVocabulary.allKeys(Scope.IMPORT));
            known.addAll(STRUCTURE);
            assertThat(known).containsAll(mentioned(prompt));
        }
        var weeksKeys = new HashSet<>(TemplateVocabulary.allKeys(Scope.IMPORT));
        weeksKeys.remove(TemplateVocabulary.key("RIR"));
        assertThat(mentioned(ImportPrompt.WEEKS)).containsAll(weeksKeys).doesNotContain(TemplateVocabulary.key("RIR"));
    }

    /** Примеры этапов 1–3 вместе: понимание даёт правила, упражнения и план, сопоставление и неделя 1 — по ним. */
    @Test
    void exampleAnswersOfTheStagesAreParsedWithoutWarnings() {
        var assembly = parse(example(ImportPrompt.UNDERSTAND));
        assertThat(assembly.snapshot(false).rules()).hasSize(2);
        assertThat(assembly.pendingWeeks()).containsExactly(1, 2);
        assertThat(assembly.unresolved()).contains("Подтягивания / верхний блок | тип=вес_повторы");
        for (var line : example(ImportPrompt.MATCH).split("\n")) {
            if (ProgramLine.parse(line) instanceof ProgramLine.ExerciseEntry entry) assembly.remap(entry);
        }
        assembly.remap((ProgramLine.ExerciseEntry) ProgramLine.parse(
                "- Подтягивания / верхний блок → Подтягивания | тип=вес_повторы | или=Тяга верхнего блока"));
        var cursor = assembly.cursor(java.util.Set.of(1));
        for (var line : example(ImportPrompt.WEEKS).split("\n")) cursor.line(line);
        assertThat(cursor.end(false)).containsExactly(1);
        var draft = assembly.snapshot(false);
        assertThat(draft.warnings()).isEmpty();
        assertThat(draft.programDraft().name()).isEqualTo("Силовая подготовка");
        assertThat(draft.plan()).extracting(ImportDraftV1.PlannedWeek::status).containsExactly(ImportDraftV1.DONE, ImportDraftV1.PENDING);

        var resolutions = draft.exerciseResolutions();
        assertThat(resolutions.getFirst().status()).isEqualTo("matched");
        assertThat(resolutions.getFirst().originalName()).isEqualTo("Присед соревновательный");
        assertThat(resolutions.getLast().status()).isEqualTo("ambiguous");
        assertThat(resolutions.getLast().candidates()).hasSize(2);

        var week1 = draft.programDraft().cycles().getFirst().weeks().getFirst();
        assertThat(week1.workouts()).extracting(ImportDraftV1.Day::dayOffset).containsExactly(0, 1);
        assertThat(week1.workouts().getFirst().title()).isEqualTo("Ноги и корпус");
        var squat = week1.workouts().getFirst().blocks().getFirst().exercises().getFirst();
        assertThat(squat.comment()).startsWith("соревновательный");
        var set = squat.sets().getFirst();
        assertThat(List.of(set.count(), set.repetitions(), set.rpe(), set.setType()))
                .containsExactly("1", "5", "7.5", "working");
        assertThat(set.note()).contains("1–2 подхода", "RPE 7–8");

        var plankSet = week1.workouts().getFirst().blocks().get(1).exercises().getFirst().sets().getFirst();
        assertThat(List.of(plankSet.count(), plankSet.durationSeconds(), plankSet.note()))
                .containsExactly("2", "20", "20–35 с");

        var pullSet = week1.workouts().get(1).blocks().getFirst().exercises().getFirst().sets().getFirst();
        assertThat(List.of(pullSet.rpe(), pullSet.note())).containsExactly("7", "RIR 3");
    }

    private static HashSet<String> mentioned(String prompt) {
        var mentioned = new HashSet<String>();
        var m = KEY_IN_TEXT.matcher(prompt);
        while (m.find()) mentioned.add(TemplateVocabulary.key(m.group(1)));
        return mentioned;
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
    void perSetRepsBecomeSeparateSets() {
        var draft = parse("""
                Программа: Тест
                Упражнения:
                - Присед → Присед со штангой | тип=вес_повторы
                План:
                Неделя: 1 | дни=Пн
                Тренировки:
                Неделя: 1
                День: Пн
                Упражнение: Присед
                Подход: кол=3 | повт=10 / 10 / 9 | вес=40 кг | RPE=8
                """).snapshot(true);
        var sets = draft.programDraft().cycles().getFirst().weeks().getFirst().workouts().getFirst().blocks().getFirst()
                .exercises().getFirst().sets();
        assertThat(sets).extracting(ImportDraftV1.Prescription::repetitions).containsExactly("10", "10", "9");
        assertThat(sets).extracting(ImportDraftV1.Prescription::count).containsOnly("1");
        assertThat(sets).extracting(ImportDraftV1.Prescription::weightKg).containsOnly("40");
        assertThat(draft.warnings()).isEmpty();
    }

    @Test
    void rulesOfThePlanReachEveryPart() {
        var assembly = parse("""
                Программа: Тест
                Упражнения:
                - Присед → Присед со штангой | тип=вес_повторы
                План:
                Правило: вес из журнала — у первого упражнения дня
                Неделя: 1 | дни=Пн
                Неделя: 2 | дни=Пн
                """);
        assertThat(assembly.snapshot(false).rules()).containsExactly("вес из журнала — у первого упражнения дня");
        var outline = assembly.outline();
        var input = new ProgramParserModel.Input(new com.sizylift.backend.imports.entity.ImportSession.Source(List.of()), MONDAY,
                List.of(), ProgramParserModel.Task.weeks(outline, List.of(2), List.of("Присед | тип=вес_повторы")), "");
        assertThat(ModelInputRenderer.render(input)).contains("Правило: вес из журнала — у первого упражнения дня");
    }

    @Test
    void aQuestionStopsTheParse() {
        var draft = parse("Вопрос: Какой лист разбирать — новичок или опытный?").snapshot(true);
        assertThat(draft.needsClarification()).isTrue();
        assertThat(draft.clarificationMessage()).startsWith("Какой лист");
    }

    /** Ответ из примера промпта: строки после «Ответ:» до конца примера. */
    private static String example(String prompt) {
        var start = prompt.indexOf("Ответ:\n") + "Ответ:\n".length();
        var end = prompt.indexOf("\n\n", start);
        return end < 0 ? prompt.substring(start) : prompt.substring(start, end);
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
