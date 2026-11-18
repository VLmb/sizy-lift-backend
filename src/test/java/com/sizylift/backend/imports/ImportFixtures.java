package com.sizylift.backend.imports;

import java.util.List;
import java.util.UUID;

/** Ответы модели строками шаблона импорта (imports/understand-, match-, weeks-prompt.v2.txt). */
final class ImportFixtures {
    static final UUID SQUAT = UUID.fromString("14000000-0000-4000-8000-000000000001");
    static final String TEXT = "Присед со штангой 3×5 100 кг @8";
    static final String DEFAULT_SET = "Подход: кол=3 | повт=5 | вес=100 кг | RPE=8";
    private static final String ARROW = " → ";
    private ImportFixtures() { }

    /** Строка списка «Упражнения»: присед из каталога. */
    static String matched() { return "- Присед" + ARROW + "Присед со штангой | тип=вес_повторы"; }
    /** Источник сам предлагает выбор — пользователь выбирает упражнение. */
    static String ambiguous() { return matched() + " | или=Румынская тяга со штангой"; }
    static String custom(String name, String type) { return "- " + name + ARROW + name + " | тип=" + type + " | новое"; }
    static String clarification(String question) { return "Вопрос: " + question + "\n"; }

    /** Небольшая программа целиком в первом ответе: одна неделя, одна тренировка. */
    static String draft(String... exercises) { return draftWithSet(DEFAULT_SET, exercises); }

    static String draftWithSet(String set, String... exercises) {
        var text = new StringBuilder(head(List.of(exercises))).append("План:\nНеделя: 1 | дни=1\nТренировки:\n");
        week(text, 1, set, exercises);
        return text.toString();
    }

    /** Каркас большой программы без тренировок: weeks недель по workouts тренировок, циклы по две недели. */
    static String plan(List<String> exercises, int weeks, int workouts) {
        var text = new StringBuilder(head(exercises)).append("План:\n");
        var days = String.join(",", java.util.stream.IntStream.rangeClosed(1, workouts).mapToObj(String::valueOf).toList());
        for (int week = 1; week <= weeks; week++)
            text.append("Неделя: ").append(week).append(" | цикл=Цикл ").append((week + 1) / 2).append(" | дни=").append(days).append('\n');
        return text.toString();
    }

    /** Ответ части: недели numbers по тренировке с каждым упражнением; extras — новые упражнения части. */
    static String weeks(List<Integer> numbers, List<String> extras, String... exercises) {
        var text = new StringBuilder();
        if (!extras.isEmpty()) text.append("Упражнения:\n").append(String.join("\n", extras)).append('\n');
        text.append("Тренировки:\n");
        for (var number : numbers) week(text, number, DEFAULT_SET, exercises);
        return text.toString();
    }

    private static String head(List<String> exercises) {
        return "Программа: Программа\nУпражнения:\n" + String.join("\n", exercises) + "\n";
    }

    private static void week(StringBuilder text, int number, String set, String... exercises) {
        text.append("Неделя: ").append(number).append("\nДень: 1 | название=Тренировка\n");
        for (var exercise : exercises) text.append("Упражнение: ").append(source(exercise)).append('\n').append(set).append('\n');
    }

    /** Название как в источнике — левая часть строки списка. */
    static String source(String entry) { return entry.substring(2, entry.indexOf(ARROW)); }
}
