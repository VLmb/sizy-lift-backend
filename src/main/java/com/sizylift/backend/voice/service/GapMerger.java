package com.sizylift.backend.voice.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.sizylift.backend.voice.dto.VoiceDtos.Proposal;
import com.sizylift.backend.voice.parser.ParsedWorkout.SetLine;
import com.sizylift.backend.voice.parser.SetLineFit;
import com.sizylift.backend.voice.parser.TextFacts;
import com.sizylift.backend.voice.service.VoiceDraft.DraftItem;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Непонятые куски для запасной модели и проверка её ответа. Каждый ответ сверяется с текстом своей дырки:
 * числа — только из неё, название и заметка — из её слов (с поправкой на ошибку распознавания в слове).
 * Не прошедший проверку ответ отбрасывается, дырка остаётся непонятой.
 */
final class GapMerger {
    enum Place { NAME, INSIDE, OUTSIDE }

    /** {@code item} — упражнение дырки (для OUTSIDE — null), {@code text} — её слова. */
    record Gap(String id, Place place, String text, DraftItem item) { }

    private static final String GAP_PREFIX = "G";
    private static final String ITEM_PREFIX = "E";
    private static final Set<String> RESULT_TYPES = Set.of("weight_reps", "added_weight_reps", "duration", "distance_time", "free");
    private static final String FIELD_NAMES = "(n|r|kg|pct|sec|m|rpe|rir|rest|type)";
    private static final Pattern FIELD = Pattern.compile(FIELD_NAMES + "=(\\S+)");
    /** Строка целиком из полей, без лишнего текста. */
    private static final Pattern LINE = Pattern.compile("\\s*" + FIELD_NAMES + "=\\S+(?:\\s+" + FIELD_NAMES + "=\\S+)*\\s*");
    private static final Pattern RANGE = Pattern.compile("(\\d{1,3})-(\\d{1,3})");
    private static final Pattern INTEGER = Pattern.compile("\\d{1,4}");
    private static final Pattern DECIMAL = Pattern.compile("\\d{1,4}(?:\\.\\d{1,2})?");
    private static final String AMRAP = "AMRAP";
    private static final double SECONDS_PER_MINUTE = 60;
    private static final double METERS_PER_KILOMETER = 1000;
    private static final double EPSILON = 1e-9;
    private static final int MAX_SETS_PER_ANSWER = 10;

    private GapMerger() { }

    static List<Gap> gaps(VoiceDraft draft) {
        var gaps = new ArrayList<Gap>();
        for (var item : draft.items) {
            if (item.unknown() && !item.spoken.isBlank()) gaps.add(new Gap(GAP_PREFIX + (gaps.size() + 1), Place.NAME, item.spoken, item));
            for (var u : item.unrecognized) gaps.add(new Gap(GAP_PREFIX + (gaps.size() + 1), Place.INSIDE, u, item));
        }
        for (var u : draft.unrecognized) gaps.add(new Gap(GAP_PREFIX + (gaps.size() + 1), Place.OUTSIDE, u, null));
        return gaps;
    }

    /** Вход модели: каталог первым (общий префикс запросов кэшируется), затем фраза, разбор и дырки. */
    static String render(String phrase, VoiceDraft draft, List<Gap> gaps, VoiceCatalog catalog) {
        var b = new StringBuilder("КАТАЛОГ\n");
        for (var e : catalog.entries()) {
            b.append(e.alias()).append('\t').append(clean(e.name()));
            if (e.shortName() != null && !e.shortName().isBlank() && !e.shortName().equals(e.name())) {
                b.append(" | ").append(clean(e.shortName()));
            }
            b.append('\n');
        }
        b.append("ФРАЗА\n").append(clean(phrase)).append("\nРАЗБОР\n");
        for (int i = 0; i < draft.items.size(); i++) {
            var item = draft.items.get(i);
            b.append(ITEM_PREFIX).append(i + 1).append(" = ").append(item.unknown() ? "?" : clean(item.exerciseName))
                    .append(": ").append(item.lines.stream().map(GapMerger::describe).collect(Collectors.joining("; ")));
            if (item.restSeconds != null) b.append("; отдых ").append(item.restSeconds).append(" с");
            b.append('\n');
        }
        b.append("ДЫРКИ\n");
        for (var g : gaps) {
            int index = g.item() == null ? -1 : draft.items.indexOf(g.item()) + 1;
            var place = switch (g.place()) {
                case NAME -> "название " + ITEM_PREFIX + index;
                case INSIDE -> "уточнение к " + ITEM_PREFIX + index;
                case OUTSIDE -> "вне упражнений";
            };
            b.append(g.id()).append('\t').append(place).append('\t').append(clean(g.text()));
            if (g.place() == Place.NAME) {
                var close = g.item().candidates.stream().map(c -> catalog.byId(c.exerciseId()).map(VoiceCatalog.Entry::alias).orElse(""))
                        .filter(a -> !a.isEmpty()).collect(Collectors.joining(", "));
                if (!close.isEmpty()) b.append("\tблизкие: ").append(close);
            }
            b.append('\n');
        }
        return b.toString();
    }

    /** Применяет проверенные ответы; возвращает число дырок, по которым модель что-то решила. */
    static int apply(ObjectMapper json, String arguments, List<Gap> gaps, VoiceDraft draft, VoiceCatalog catalog) {
        JsonNode root;
        try {
            root = json.readTree(arguments);
        } catch (JacksonException e) {
            return 0;
        }
        Map<String, Gap> byId = new HashMap<>();
        gaps.forEach(g -> byId.put(g.id(), g));
        var done = new HashSet<String>();
        int resolved = 0;
        for (var answer : root.path("gaps")) {
            var gap = byId.get(answer.path("gap").asString(""));
            if (gap == null || !done.add(gap.id())) continue;
            if (applyOne(answer, gap, draft, catalog)) resolved++;
        }
        return resolved;
    }

    private static boolean applyOne(JsonNode answer, Gap gap, VoiceDraft draft, VoiceCatalog catalog) {
        var kind = answer.path("kind").asString("");
        return switch (kind) {
            case "exercise" -> gap.place() == Place.NAME && exercise(answer, gap, catalog);
            case "note" -> gap.place() == Place.INSIDE && note(answer.path("note").asString(""), gap);
            case "sets" -> gap.place() == Place.INSIDE && sets(answer.path("sets"), gap);
            case "filler" -> gap.place() != Place.NAME && removeFragment(gap, draft);
            default -> false;
        };
    }

    private static boolean exercise(JsonNode answer, Gap gap, VoiceCatalog catalog) {
        var item = gap.item();
        var match = catalog.byAlias(answer.path("match").asString(""));
        if (match.isPresent()) {
            var e = match.get();
            item.exerciseId = e.id();
            item.exerciseName = e.name();
            item.resultType = e.resultType();
            item.confidence = "medium";
            item.proposal = null;
            item.lines = SetLineFit.fit(item.lines, e.resultType(), item.issues);
            return true;
        }
        var name = answer.path("newName").asString("").strip();
        if (name.isEmpty() || !fromWords(name, gap.text())) return false;
        var type = answer.path("resultType").asString("");
        item.proposal = new Proposal(VoiceDraft.capitalized(name), RESULT_TYPES.contains(type) ? type : null, VoiceDraft.SOURCE_MODEL);
        return true;
    }

    private static boolean note(String note, Gap gap) {
        var text = note.strip();
        if (text.isEmpty() || !fromWords(text, gap.text())) return false;
        gap.item().notes.add(text);
        gap.item().unrecognized.remove(gap.text());
        return true;
    }

    private static boolean sets(JsonNode sets, Gap gap) {
        if (!sets.isArray() || sets.isEmpty() || sets.size() > MAX_SETS_PER_ANSWER) return false;
        var numbers = TextFacts.numbers(gap.text());
        var lines = new ArrayList<SetLine>();
        Integer rest = null;
        for (var s : sets) {
            var parsed = setLine(s.asString(""), numbers);
            if (parsed == null) return false;
            lines.add(parsed.line());
            if (parsed.rest() != null) rest = parsed.rest();
        }
        var item = gap.item();
        item.lines = new ArrayList<>(item.lines);
        item.lines.addAll(SetLineFit.fit(lines, item.resultType, item.issues));
        if (rest != null && item.restSeconds == null) item.restSeconds = rest;
        item.unrecognized.remove(gap.text());
        return true;
    }

    private record Parsed(SetLine line, Integer rest) { }

    /** Строка «n=3 r=10 kg=20»; любое число должно быть сказано в дырке (секунды — возможно, минутами). */
    private static Parsed setLine(String text, List<Double> numbers) {
        if (!LINE.matcher(text).matches()) return null;
        var fields = new HashMap<String, String>();
        var m = FIELD.matcher(text);
        while (m.find()) if (fields.put(m.group(1), m.group(2)) != null) return null;
        Integer count = null, reps = null, repsMax = null, sec = null, meters = null, rest = null;
        Double kg = null, pct = null, rpe = null, rir = null;
        boolean failure = false;
        for (var f : fields.entrySet()) {
            var v = f.getValue();
            switch (f.getKey()) {
                case "n" -> { count = integer(v); if (count == null || count != 1 && !said(count, numbers)) return null; }
                case "r" -> {
                    if (AMRAP.equals(v)) { failure = true; continue; }
                    var range = RANGE.matcher(v);
                    if (range.matches()) { reps = Integer.parseInt(range.group(1)); repsMax = Integer.parseInt(range.group(2)); }
                    else reps = integer(v);
                    if (reps == null || !said(reps, numbers) || repsMax != null && !said(repsMax, numbers)) return null;
                }
                case "kg" -> { kg = decimal(v); if (kg == null || !said(kg, numbers)) return null; }
                case "pct" -> { pct = decimal(v); if (pct == null || !said(pct, numbers)) return null; }
                case "rpe" -> { rpe = decimal(v); if (rpe == null || !said(rpe, numbers)) return null; }
                case "rir" -> { rir = decimal(v); if (rir == null || !said(rir, numbers)) return null; }
                case "sec" -> { sec = integer(v); if (sec == null || !time(sec, numbers)) return null; }
                case "rest" -> { rest = integer(v); if (rest == null || !time(rest, numbers)) return null; }
                case "m" -> { meters = integer(v); if (meters == null || !said(meters, numbers) && !said(meters / METERS_PER_KILOMETER, numbers)) return null; }
                default -> { }
            }
        }
        return new Parsed(new SetLine(count, reps, repsMax, failure, kg, pct, sec, meters, rpe, rir, null, null), rest);
    }

    private static boolean time(int seconds, List<Double> numbers) {
        return said(seconds, numbers) || said(seconds / SECONDS_PER_MINUTE, numbers);
    }

    private static boolean said(double value, List<Double> numbers) {
        return numbers.stream().anyMatch(n -> Math.abs(n - value) < EPSILON);
    }

    private static Integer integer(String v) { return INTEGER.matcher(v).matches() ? Integer.parseInt(v) : null; }

    private static Double decimal(String v) { return DECIMAL.matcher(v).matches() ? Double.parseDouble(v) : null; }

    /** Каждое слово ответа — слово дырки, возможно с исправленной ошибкой распознавания. */
    private static boolean fromWords(String answer, String gapText) {
        var gap = TextFacts.stems(gapText);
        var words = TextFacts.stems(answer);
        return !words.isEmpty() && words.stream().allMatch(w -> gap.stream().anyMatch(g -> TextFacts.similar(w, g)));
    }

    private static boolean removeFragment(Gap gap, VoiceDraft draft) {
        return gap.place() == Place.INSIDE ? gap.item().unrecognized.remove(gap.text()) : draft.unrecognized.remove(gap.text());
    }

    private static String describe(SetLine l) {
        var parts = new ArrayList<String>();
        parts.add((l.count() == null ? "?" : l.count()) + "×" + (l.toFailure() ? "до отказа"
                : l.reps() == null ? "?" : l.reps() + (l.repsMax() == null ? "" : "-" + l.repsMax())));
        if (l.weightKg() != null) parts.add(l.weightKg() + " кг");
        if (l.percent() != null) parts.add(l.percent() + " %");
        if (l.seconds() != null) parts.add(l.seconds() + " с");
        if (l.meters() != null) parts.add(l.meters() + " м");
        if (l.rpe() != null) parts.add("RPE " + l.rpe());
        if (l.rir() != null) parts.add("RIR " + l.rir());
        return String.join(" ", parts);
    }

    /** Переносы и табуляции внутри значения ломают разметку входа. */
    private static String clean(String s) { return s.replace('\n', ' ').replace('\t', ' '); }
}
