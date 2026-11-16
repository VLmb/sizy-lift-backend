package com.sizylift.backend.imports.template;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Одна строка шаблона импорта (docs/imports.md, «Шаблон»). Разбор строгий и без контекста: что строка значит
 * в программе, решает {@link ImportAssembly}. Ключи строки — {@code голова | ключ=значение | флаг}; часть без
 * известного ключа продолжает предыдущее значение, поэтому в описании и заметках могут быть «|» и «=».
 */
public sealed interface ProgramLine {
    record Question(String text) implements ProgramLine { }
    /** Правило чтения источника из каркаса: части программы разбираются отдельно и следуют одним правилам. */
    record Rule(String text) implements ProgramLine { }
    record Program(String name, String description) implements ProgramLine { }
    record Section(Kind kind) implements ProgramLine {
        public enum Kind { EXERCISES, PLAN, WORKOUTS }
    }
    /** «- источник → название | тип=… | новое | или=а; б». */
    record ExerciseEntry(String source, String name, String typeWord, boolean marked, List<String> alternatives)
            implements ProgramLine { }
    /** «Неделя: 2-4 | как=1 | цикл=… | название=… | дни=Пн,Ср». {@code copyOf} — null, если неделя своя. */
    record Week(int from, int to, Integer copyOf, String cycle, String name, List<String> days) implements ProgramLine { }
    record Day(String label, String name) implements ProgramLine { }
    /** {@code kind} пустой — конец блока («Блок: —»). */
    record Block(String kind, String name, String note) implements ProgramLine { }
    record Item(String name) implements ProgramLine { }
    record SetRow(String body) implements ProgramLine { }
    record Comment(String text) implements ProgramLine { }
    record Unknown(String text) implements ProgramLine { }

    int MAX_WEEK = 104;
    Pattern LABEL = Pattern.compile("^([А-Яа-яЁё ]+?)\\s*:\\s*(.*)$");
    Pattern ENTRY = Pattern.compile("^[-–•*]\\s*(.+)$");
    /** «Неделя 2», «День Пн» без двоеточия — та же строка, что с двоеточием. */
    Pattern BARE = Pattern.compile("^(неделя|день)\\s+(.+)$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    Pattern ARROW = Pattern.compile("\\s*(?:→|->|=>)\\s*");
    Pattern WEEKS = Pattern.compile("^(\\d{1,3})(?:\\s*[-–]\\s*(\\d{1,3}))?$");
    Pattern NUMBER = Pattern.compile("^\\d{1,3}$");
    Pattern KEY = Pattern.compile("^([а-яёa-z_]+)\\s*=\\s*(.*)$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    Set<String> KEYS = Set.of("описание", "тип", "или", "цикл", "название", "дни", "как", "заметка");
    String MARK_NEW = "новое";
    Set<String> END_BLOCK = Set.of("", "-", "—", "–", "конец", "нет");

    static ProgramLine parse(String raw) {
        var text = raw.strip();
        var entry = ENTRY.matcher(text);
        if (entry.matches() && !text.startsWith("--")) return exercise(entry.group(1));
        var labelled = LABEL.matcher(text);
        if (!labelled.matches()) labelled = LABEL.matcher(BARE.matcher(text).replaceFirst("$1: $2"));
        if (!labelled.matches()) return new Unknown(text);
        var label = labelled.group(1).strip().toLowerCase(Locale.ROOT).replace('ё', 'е');
        var rest = labelled.group(2).strip();
        return switch (label) {
            case "вопрос" -> rest.isEmpty() ? new Unknown(text) : new Question(rest);
            case "правило" -> rest.isEmpty() ? new Unknown(text) : new Rule(rest);
            case "программа" -> program(rest);
            case "упражнения" -> new Section(Section.Kind.EXERCISES);
            case "план" -> new Section(Section.Kind.PLAN);
            case "тренировки" -> new Section(Section.Kind.WORKOUTS);
            case "неделя", "недели" -> week(rest, text);
            case "день" -> day(rest, text);
            case "блок" -> block(rest);
            case "упражнение" -> rest.isEmpty() ? new Unknown(text) : new Item(rest);
            case "подход", "подходы" -> new SetRow(rest);
            case "комментарий" -> rest.isEmpty() ? new Unknown(text) : new Comment(rest);
            default -> new Unknown(text);
        };
    }

    private static ProgramLine program(String rest) {
        var parts = Parts.of(rest);
        return new Program(parts.head(), parts.value("описание"));
    }

    private static ProgramLine exercise(String rest) {
        var parts = Parts.of(rest);
        var names = ARROW.split(parts.head(), 2);
        var source = names[0].strip();
        var name = names.length > 1 && !names[1].isBlank() ? names[1].strip() : source;
        var alternatives = Arrays.stream(parts.value("или").split(";")).map(String::strip).filter(s -> !s.isEmpty()).toList();
        return new ExerciseEntry(source, name, parts.value("тип"), parts.flags().contains(MARK_NEW), alternatives);
    }

    private static ProgramLine week(String rest, String text) {
        var parts = Parts.of(rest);
        var m = WEEKS.matcher(parts.head());
        if (!m.matches()) return new Unknown(text);
        int from = Integer.parseInt(m.group(1));
        int to = m.group(2) == null ? from : Integer.parseInt(m.group(2));
        if (from < 1 || to < from || to > MAX_WEEK) return new Unknown(text);
        var copy = parts.value("как");
        Integer copyOf = NUMBER.matcher(copy).matches() ? Integer.valueOf(copy) : null;
        var days = Arrays.stream(parts.value("дни").split("[,;]")).map(String::strip).filter(s -> !s.isEmpty()).toList();
        return new Week(from, to, copyOf, parts.value("цикл"), parts.value("название"), days);
    }

    private static ProgramLine day(String rest, String text) {
        var parts = Parts.of(rest);
        return parts.head().isEmpty() ? new Unknown(text) : new Day(parts.head(), parts.value("название"));
    }

    private static ProgramLine block(String rest) {
        var parts = Parts.of(rest);
        var kind = parts.head().toLowerCase(Locale.ROOT).replace('ё', 'е');
        return new Block(END_BLOCK.contains(kind) ? "" : kind, parts.value("название"), parts.value("заметка"));
    }

    /** Голова строки, значения известных ключей и флаги без «=». */
    record Parts(String head, Map<String, String> values, List<String> flags) {
        static Parts of(String text) {
            var pieces = text.split("\\|");
            var values = new LinkedHashMap<String, String>();
            var flags = new ArrayList<String>();
            String last = null;
            for (int i = 1; i < pieces.length; i++) {
                var piece = pieces[i].strip();
                var m = KEY.matcher(piece);
                var key = m.matches() ? m.group(1).toLowerCase(Locale.ROOT).replace('ё', 'е') : null;
                if (key != null && KEYS.contains(key)) {
                    values.putIfAbsent(key, m.group(2).strip());
                    last = key;
                } else if (piece.equalsIgnoreCase(MARK_NEW)) {
                    flags.add(MARK_NEW);
                } else if (last != null) {
                    values.put(last, values.get(last) + " | " + piece);
                }
            }
            return new Parts(pieces[0].strip(), values, flags);
        }

        String value(String key) { return values.getOrDefault(key, "").strip(); }
    }
}
