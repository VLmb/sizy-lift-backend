package com.sizylift.backend.voice.template;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sizylift.backend.voice.dto.VoiceDtos.Candidate;
import com.sizylift.backend.voice.dto.VoiceDtos.Item;
import com.sizylift.backend.voice.dto.VoiceDtos.Line;
import com.sizylift.backend.voice.dto.VoiceDtos.Proposal;
import com.sizylift.backend.voice.parser.TextFacts;
import com.sizylift.backend.voice.service.VoiceCatalog;

/**
 * Строгий разборщик шаблона модели (docs/voice.md, «Разбор шаблоном»). Никакой живой речи: строки с метками,
 * ключи с фиксированным форматом значений. Что не по формату — пропускается и попадает в {@link Result#skipped},
 * без угадывания. Каждое число сверяется с фразой: модель могла его выдумать.
 */
public final class TemplateParser {
    static final String HIGH = "high";
    /** Найдено по вариантам названия или с опечаткой, а не по точному названию каталога. */
    static final String MEDIUM = "medium";
    static final String LOW = "low";
    private static final String SOURCE_MODEL = "model";
    private static final int MAX_ITEMS = 30;
    private static final int MAX_LINES = 30;
    private static final int MAX_SETS = 20;
    private static final int MAX_REPS = 1000;
    private static final double MAX_WEIGHT = 1000;
    private static final double MAX_RPE = 10;
    /** Неявная единица: «по минуте», «один подход» — во фразе нет числа 1. */
    private static final double IMPLICIT = 1;
    private static final double EPSILON = 1e-6;

    private static final Pattern EXERCISE = Pattern.compile(
            "^упражнение\\s*:\\s*(.+?)\\s*(?:\\|\\s*тип\\s*=\\s*([а-яё_]+)\\s*)?(\\|\\s*новое\\s*)?$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern SET = Pattern.compile("^подход\\s*:\\s*(.+)$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern COMMENT = Pattern.compile("^комментарий\\s*:\\s*(.+)$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** Заметка — последним ключом: её текст может содержать «|» и «=». */
    private static final String NOTE_KEY = "заметка";
    private static final Pattern NOTE = Pattern.compile("(?:^|\\|)\\s*" + NOTE_KEY + "\\s*=\\s*(.*)$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern PAIR = Pattern.compile("^([а-яёa-z_]+)\\s*=\\s*(.+)$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final String NUMBER = "(\\d+(?:[.,]\\d+)?)";
    private static final Pattern COUNT = Pattern.compile("^(\\d{1,2})$");
    private static final Pattern REPS = Pattern.compile("^(\\d{1,4})(?:\\s*[-–]\\s*(\\d{1,4}))?$");
    private static final Pattern KG = Pattern.compile("^" + NUMBER + "\\s*(?:кг)?$");
    private static final Pattern PERCENT = Pattern.compile("^" + NUMBER + "\\s*%?$");
    private static final Pattern PLAIN = Pattern.compile("^" + NUMBER + "$");
    private static final Pattern TIME = Pattern.compile("^" + NUMBER + "\\s*(с|сек|мин|ч)\\.?$");
    private static final Pattern DISTANCE = Pattern.compile("^" + NUMBER + "\\s*(м|км)\\.?$");

    /** Как читается значение ключа. Ключи и их допустимость по типам — в словаре {@link TemplateVocabulary}. */
    enum Format { COUNT, REPS, KILOGRAMS, PERCENT, RPE, TIME, DISTANCE, SET_TYPE, NOTE }

    /** Формат каждого ключа словаря; тест сверяет, что ключей без формата и форматов без ключа нет. */
    static final Map<String, Format> FORMATS = Map.ofEntries(
            Map.entry("кол", Format.COUNT), Map.entry("повт", Format.REPS), Map.entry("вес", Format.KILOGRAMS),
            Map.entry("доп_вес", Format.KILOGRAMS), Map.entry("процент", Format.PERCENT), Map.entry("rpe", Format.RPE),
            Map.entry("время", Format.TIME), Map.entry("дистанция", Format.DISTANCE), Map.entry("тип", Format.SET_TYPE),
            Map.entry("отдых", Format.TIME), Map.entry(NOTE_KEY, Format.NOTE));

    /** {@code skipped} — что не по формату, не подходит типу или чего нет во фразе: для отладки и метрик. */
    public record Result(List<Item> items, List<String> skipped) { }

    private TemplateParser() { }

    public static Result parse(String template, String phrase, VoiceCatalog catalog) {
        var spoken = new Spoken(phrase);
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
                    var line = set(m.group(1), current, spoken, skipped);
                    if (line != null) current.lines.add(line);
                }
            } else if ((m = COMMENT.matcher(text)).matches() && current != null) {
                current.comments.add(m.group(1).strip());
            } else {
                skipped.add("строка: " + text);
            }
        }
        return new Result(items.stream().map(Draft::item).toList(), List.copyOf(skipped));
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

    /** Подход; null — строка без единого значения. */
    private static Line set(String body, Draft d, Spoken spoken, List<String> skipped) {
        String note = null;
        var noteMatch = NOTE.matcher(body);
        if (noteMatch.find()) {
            note = noteMatch.group(1).strip();
            body = body.substring(0, noteMatch.start());
            if (note.isEmpty()) note = null;
        }
        var allowed = TemplateVocabulary.keysFor(d.resultType);
        var common = TemplateVocabulary.commonKeys().stream().map(TemplateVocabulary::key).collect(Collectors.toSet());
        var label = d.name != null ? d.name : d.spoken;
        var values = new LinkedHashMap<String, String>();
        for (var part : body.split("\\|")) {
            var text = part.strip();
            if (text.isEmpty()) continue;
            var m = PAIR.matcher(text);
            if (!m.matches()) {
                skipped.add(label + ": «" + text + "»");
                continue;
            }
            var k = m.group(1).toLowerCase(Locale.ROOT);
            if (!FORMATS.containsKey(k) || !TemplateVocabulary.allKeys().contains(k)) {
                skipped.add(label + ": неизвестный ключ «" + text + "»");
                continue;
            }
            if (!common.contains(k) && !allowed.contains(k)) {
                d.issues.add(text + " — не подходит типу упражнения");
                continue;
            }
            values.putIfAbsent(k, m.group(2).strip());
        }
        var line = new Values(label, spoken, skipped);
        Integer count = line.integer(values.get("кол"), COUNT, 1, MAX_SETS);
        Integer reps = null;
        Integer repsMax = null;
        var repsText = values.get("повт");
        if (repsText != null) {
            var m = REPS.matcher(repsText);
            if (m.matches() && line.said(m.group(1)) && (m.group(2) == null || line.said(m.group(2)))) {
                reps = bounded(Integer.parseInt(m.group(1)), MAX_REPS);
                repsMax = m.group(2) == null ? null : bounded(Integer.parseInt(m.group(2)), MAX_REPS);
                if (reps != null && repsMax != null && repsMax <= reps) repsMax = null;
            } else {
                line.reject("повт", repsText);
            }
        }
        Double weight = line.decimal(values.containsKey("вес") ? values.get("вес") : values.get("доп_вес"),
                values.containsKey("вес") ? "вес" : "доп_вес", KG, MAX_WEIGHT);
        Double percent = line.decimal(values.get("процент"), "процент", PERCENT, MAX_WEIGHT);
        Double rpe = line.rpe(values.get("rpe"));
        Integer seconds = line.seconds(values.get("время"), "время");
        Integer meters = line.meters(values.get("дистанция"));
        Integer rest = line.seconds(values.get("отдых"), "отдых");
        String type = null;
        var typeText = values.get("тип");
        if (typeText != null) {
            type = TemplateVocabulary.setType(typeText).orElse(null);
            if (type == null) line.reject("тип", typeText);
        }
        if (reps == null && weight == null && percent == null && rpe == null && seconds == null && meters == null
                && note == null && type == null && rest == null && values.get("кол") == null) {
            return null;
        }
        return new Line(count, reps, repsMax, false, weight, percent, seconds, meters, rpe, null, type, note, rest);
    }

    /** Значения одной строки подходов: формат, пределы и сверка с фразой. */
    private record Values(String exercise, Spoken spoken, List<String> skipped) {
        void reject(String key, String value) { skipped.add(exercise + ": " + key + "=" + value); }

        boolean said(String number) { return spoken.has(parse(number)); }

        /**
         * Число подходов. Кроме сказанного — на один меньше сказанного: «три по десять, последний до отказа»
         * по правилу промпта превращается в 2 подхода и 1 отдельный.
         */
        Integer integer(String text, Pattern format, int fallback, int max) {
            if (text == null) return null;
            var m = format.matcher(text);
            if (!m.matches() || !(said(m.group(1)) || spoken.has(parse(m.group(1)) + 1))) {
                reject("кол", text);
                return null;
            }
            var value = Integer.parseInt(m.group(1));
            return value < fallback || value > max ? null : value;
        }

        Double decimal(String text, String key, Pattern format, double max) {
            if (text == null) return null;
            var m = format.matcher(text.toLowerCase(Locale.ROOT));
            if (!m.matches() || !said(m.group(1))) {
                reject(key, text);
                return null;
            }
            var value = parse(m.group(1));
            return value <= 0 || value > max ? null : value;
        }

        /** RPE 10 — «до отказа», «на максимум»: числа 10 во фразе может не быть. */
        Double rpe(String text) {
            if (text == null) return null;
            var m = PLAIN.matcher(text);
            if (!m.matches()) {
                reject("RPE", text);
                return null;
            }
            var value = parse(m.group(1));
            boolean failure = Math.abs(value - MAX_RPE) < EPSILON && spoken.failure();
            if (!failure && !spoken.has(value)) {
                reject("RPE", text);
                return null;
            }
            return value < 1 || value > MAX_RPE ? null : value;
        }

        Integer seconds(String text, String key) {
            if (text == null) return null;
            var m = TIME.matcher(text.toLowerCase(Locale.ROOT));
            if (!m.matches() || !said(m.group(1))) {
                reject(key, text);
                return null;
            }
            var value = parse(m.group(1));
            var unit = m.group(2);
            int factor = unit.startsWith("мин") ? 60 : "ч".equals(unit) ? 3600 : 1;
            var total = (int) Math.round(value * factor);
            return total <= 0 ? null : total;
        }

        Integer meters(String text) {
            if (text == null) return null;
            var m = DISTANCE.matcher(text.toLowerCase(Locale.ROOT));
            if (!m.matches() || !said(m.group(1))) {
                reject("дистанция", text);
                return null;
            }
            var total = (int) Math.round(parse(m.group(1)) * ("км".equals(m.group(2)) ? 1000 : 1));
            return total <= 0 ? null : total;
        }
    }

    /** Что сказано во фразе: числа (словами и цифрами) и было ли «до отказа». */
    private static final class Spoken {
        private final List<Double> numbers;
        private final boolean failure;

        Spoken(String phrase) {
            numbers = TextFacts.numbers(phrase);
            var lower = phrase.toLowerCase(Locale.ROOT);
            failure = lower.contains("отказ") || lower.contains("максимум");
        }

        boolean has(double value) {
            return Math.abs(value - IMPLICIT) < EPSILON || numbers.stream().anyMatch(n -> Math.abs(n - value) < EPSILON);
        }

        boolean failure() { return failure; }
    }

    private static Integer bounded(int value, int max) { return value < 1 || value > max ? null : value; }

    private static double parse(String number) { return Double.parseDouble(number.replace(',', '.')); }

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
