package com.sizylift.backend.voice.template;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sizylift.backend.voice.dto.VoiceDtos.Candidate;
import com.sizylift.backend.voice.dto.VoiceDtos.Item;
import com.sizylift.backend.voice.dto.VoiceDtos.Line;
import com.sizylift.backend.voice.dto.VoiceDtos.Proposal;
import com.sizylift.backend.template.SetLine;
import com.sizylift.backend.template.TemplateVocabulary;
import com.sizylift.backend.template.TemplateVocabulary.Scope;
import com.sizylift.backend.voice.service.VoiceCatalog;

/**
 * Строгий разборщик шаблона модели (docs/voice.md, «Разбор шаблоном»). Никакой живой речи: строки с метками,
 * ключи с фиксированным форматом значений. Что не по формату — пропускается и попадает в {@link Result#skipped},
 * без угадывания.
 */
public final class TemplateParser {
    static final String HIGH = "high";
    /** Найдено по вариантам названия или с опечаткой, а не по точному названию каталога. */
    static final String MEDIUM = "medium";
    static final String LOW = "low";
    private static final String SOURCE_MODEL = "model";
    private static final int MAX_ITEMS = 30;
    private static final int MAX_LINES = 30;
    private static final Pattern EXERCISE = Pattern.compile(
            "^упражнение\\s*:\\s*(.+?)\\s*(?:\\|\\s*тип\\s*=\\s*([а-яё_]+)\\s*)?(\\|\\s*новое\\s*)?$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern SET = Pattern.compile("^подход\\s*:\\s*(.+)$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern COMMENT = Pattern.compile("^комментарий\\s*:\\s*(.+)$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** {@code skipped} — что не по формату, не подходит типу или чего нет во фразе: для отладки и метрик. */
    public record Result(List<Item> items, List<String> skipped) { }

    private TemplateParser() { }

    public static Result parse(String template, VoiceCatalog catalog) {
        var skipped = new ArrayList<String>();
        var items = new ArrayList<Draft>();
        Draft current = null;
        for (var raw : template.split("\\R")) {
            var text = raw.strip();
            if (text.isEmpty()) continue;
            Matcher m;
            if ((m = EXERCISE.matcher(text)).matches()) {
                if (items.size() >= MAX_ITEMS) break;
                current = exercise(m.group(1), m.group(2), m.group(3) != null, catalog);
                items.add(current);
            } else if ((m = SET.matcher(text)).matches() && current != null) {
                if (current.lines.size() < MAX_LINES) {
                    var line = set(m.group(1), current, skipped);
                    if (line != null) current.lines.add(line);
                }
            } else if ((m = COMMENT.matcher(text)).matches() && current != null) {
                current.comments.add(m.group(1).strip());
            } else {
                skipped.add("строка: " + text);
            }
        }
        for (var d : items) {
            if (d.empty()) skipped.add("пустое упражнение: " + (d.name != null ? d.name : d.spoken));
        }
        return new Result(items.stream().filter(d -> !d.empty()).map(Draft::item).toList(), List.copyOf(skipped));
    }

    /** Подход «один, без значений» — то же, что ничего не сказано. */
    private static boolean blank(Line l) {
        return (l.count() == null || l.count() == 1) && l.reps() == null && !l.toFailure() && l.weightKg() == null
                && l.percent() == null && l.rpe() == null && l.durationSeconds() == null && l.distanceMeters() == null
                && l.type() == null && l.note() == null && l.restSeconds() == null;
    }

    private static final class Draft {
        UUID id;
        String name;
        String resultType;
        String spoken;
        String confidence;
        List<Candidate> candidates = List.of();
        Proposal proposal;
        final List<Line> lines = new ArrayList<>();
        final List<String> comments = new ArrayList<>();
        final List<String> issues = new ArrayList<>();

        /**
         * Упражнение без единого значения и без комментария — упомянуто, но не задано («потом пойдём на жим»):
         * в тренировку не попадает.
         */
        boolean empty() { return comments.isEmpty() && lines.stream().allMatch(TemplateParser::blank); }

        Item item() {
            var rests = lines.stream().map(Line::restSeconds).collect(HashSet<Integer>::new, HashSet::add, HashSet::addAll);
            // Отдых упражнения — для приложений, которые не знают отдыха у подхода: только если он у всех одинаков.
            Integer rest = rests.size() == 1 ? rests.iterator().next() : null;
            return new Item(id, name, resultType, spoken, confidence, rest, List.copyOf(lines), List.of(), List.of(),
                    List.copyOf(issues), candidates, proposal, comments.isEmpty() ? null : String.join("; ", comments));
        }
    }

    private static Draft exercise(String name, String type, boolean marked, VoiceCatalog catalog) {
        var d = new Draft();
        d.spoken = name;
        var key = key(name);
        var exact = catalog.entries().stream().filter(e -> key(e.name()).equals(key)).findFirst();
        if (exact.isPresent()) {
            set(d, exact.get().id(), exact.get().name(), exact.get().resultType(), HIGH);
            return d;
        }
        var found = catalog.dictionary().find(name);
        if (found != null) {
            var e = found.exercise();
            set(d, UUID.fromString(e.id()), e.name(), e.resultType(), found.exact() && !marked ? HIGH : MEDIUM);
            return d;
        }
        d.confidence = LOW;
        d.resultType = templateType(type);
        d.candidates = catalog.dictionary().similar(name).stream()
                .map(e -> new Candidate(UUID.fromString(e.id()), e.name())).toList();
        d.proposal = new Proposal(capitalized(name), d.resultType, SOURCE_MODEL);
        return d;
    }

    private static void set(Draft d, UUID id, String name, String resultType, String confidence) {
        d.id = id;
        d.name = name;
        d.resultType = resultType;
        d.confidence = confidence;
    }

    /** Подход; null — строка без единого значения. Разбор строки — общий с импортом ({@link SetLine}). */
    private static Line set(String body, Draft d, List<String> skipped) {
        var v = SetLine.parse(body, d.resultType, Scope.VOICE, d.name != null ? d.name : d.spoken, skipped, d.issues);
        if (v == null) return null;
        return new Line(v.count(), v.reps(), v.repsMax(), v.maxReps(), v.weightKg(), v.percent(), v.seconds(), v.meters(),
                v.rpe(), null, v.type(), v.note(), v.restSeconds());
    }

    private static String key(String name) {
        return name.strip().toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("\\s+", " ");
    }

    private static String templateType(String type) {
        return type == null ? null : TemplateVocabulary.resultType(type).orElse(null);
    }

    private static String capitalized(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }
}
