package com.sizylift.backend.template;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.sizylift.backend.template.TemplateVocabulary.Scope;

/**
 * Строгий разбор тела строки «Подход:» шаблона — общий для голосового ввода и импорта программ:
 * {@code кол=3 | повт=8-12 | вес=100 кг | RPE=8 | отдых=2 мин | заметка=…}. Ключи — в любом порядке; заметка
 * тоже ключ: её текст идёт до следующего « | известный_ключ=», поэтому в нём могут быть «|» и «=».
 * Значение не по формату пропускается и попадает в {@code skipped} без угадывания; ключ, не подходящий типу
 * упражнения, — в {@code issues}.
 */
public final class SetLine {
    static final String NOTE_KEY = "заметка";
    private static final int MAX_REPS = 1000;
    private static final double MAX_WEIGHT = 1000;
    private static final double MAX_RPE = 10;
    private static final int SECONDS_IN_MINUTE = 60;
    private static final int SECONDS_IN_HOUR = 3600;
    private static final int METERS_IN_KILOMETER = 1000;
    private static final Pattern PAIR = Pattern.compile("^([а-яёa-z_]+)\\s*=\\s*(.+)$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final String NUMBER = "(\\d+(?:[.,]\\d+)?)";
    private static final Pattern COUNT = Pattern.compile("^(\\d{1,3})$");
    private static final Pattern REPS = Pattern.compile("^(\\d{1,4})(?:\\s*[-–]\\s*(\\d{1,4}))?$");
    private static final Pattern MAX_REPS_WORD = Pattern.compile("^(макс|максимум|amrap|до отказа)$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern KG = Pattern.compile("^" + NUMBER + "\\s*(?:кг)?$");
    private static final Pattern PERCENT = Pattern.compile("^" + NUMBER + "\\s*%?$");
    private static final Pattern PLAIN = Pattern.compile("^" + NUMBER + "$");
    private static final Pattern RANGE = Pattern.compile("^" + NUMBER + "(?:\\s*[-–]\\s*" + NUMBER + ")?$");
    private static final Pattern TIME = Pattern.compile("^" + NUMBER + "\\s*(с|сек|мин|ч)\\.?$");
    private static final Pattern DISTANCE = Pattern.compile("^" + NUMBER + "\\s*(м|км)\\.?$");

    /** Как читается значение ключа. Ключи и их допустимость по типам — в словаре {@link TemplateVocabulary}. */
    public enum Format { COUNT, REPS, KILOGRAMS, PERCENT, RPE, RIR, TIME, DISTANCE, SET_TYPE, NOTE }

    /** Формат каждого ключа словаря; тест сверяет, что ключей без формата и форматов без ключа нет. */
    public static final Map<String, Format> FORMATS = Map.ofEntries(
            Map.entry("кол", Format.COUNT), Map.entry("повт", Format.REPS), Map.entry("вес", Format.KILOGRAMS),
            Map.entry("доп_вес", Format.KILOGRAMS), Map.entry("процент", Format.PERCENT), Map.entry("rpe", Format.RPE),
            Map.entry("rir", Format.RIR), Map.entry("время", Format.TIME), Map.entry("дистанция", Format.DISTANCE),
            Map.entry("тип", Format.SET_TYPE), Map.entry("отдых", Format.TIME), Map.entry(NOTE_KEY, Format.NOTE));

    /**
     * Значения одной строки подходов; null — не названо. {@code maxReps} — «до отказа» вместо числа повторов,
     * {@code rir} — число или диапазон строкой. Вес и доп. вес — одно поле: тип упражнения задаёт смысл.
     */
    public record Values(Integer count, Integer reps, Integer repsMax, boolean maxReps, Double weightKg, Double percent,
                         Double rpe, String rir, Integer seconds, Integer meters, Integer restSeconds, String type,
                         String note) {
        /** Подход «один, без значений» — то же, что ничего не сказано. */
        public boolean blank() {
            return (count == null || count == 1) && reps == null && !maxReps && weightKg == null && percent == null
                    && rpe == null && rir == null && seconds == null && meters == null && type == null && note == null
                    && restSeconds == null;
        }
    }

    private SetLine() { }

    /**
     * @param resultType тип упражнения: какие ключи значений допустимы; null — любые
     * @param label      название упражнения для сообщений в {@code skipped}
     * @return значения или null, если в строке не названо ни одного значения
     */
    public static Values parse(String body, String resultType, Scope scope, String label, List<String> skipped, List<String> issues) {
        var allowed = TemplateVocabulary.keysFor(resultType, scope);
        var common = TemplateVocabulary.commonKeys().stream().map(TemplateVocabulary::key).collect(Collectors.toSet());
        var known = TemplateVocabulary.allKeys(scope);
        var values = new LinkedHashMap<String, String>();
        StringBuilder note = null;
        boolean inNote = false;
        for (var part : body.split("\\|")) {
            var text = part.strip();
            var m = PAIR.matcher(text);
            var k = m.matches() ? m.group(1).toLowerCase(Locale.ROOT) : null;
            if (k == null || !known.contains(k)) {
                if (inNote) note.append(" | ").append(text);
                else if (!text.isEmpty()) skipped.add(label + ": «" + text + "»");
                continue;
            }
            inNote = NOTE_KEY.equals(k);
            if (inNote) {
                note = new StringBuilder(m.group(2).strip());
                continue;
            }
            if (!common.contains(k) && !allowed.contains(k)) {
                issues.add(text + " — не подходит типу упражнения");
                continue;
            }
            values.putIfAbsent(k, m.group(2).strip());
        }
        var line = new Reader(label, skipped);
        Integer count = line.count(values.get("кол"), scope.maxSets());
        Integer reps = null;
        Integer repsMax = null;
        boolean maxReps = false;
        var repsText = values.get("повт");
        if (repsText != null) {
            var m = REPS.matcher(repsText);
            if (m.matches()) {
                reps = bounded(Integer.parseInt(m.group(1)), MAX_REPS);
                repsMax = m.group(2) == null ? null : bounded(Integer.parseInt(m.group(2)), MAX_REPS);
                if (reps != null && repsMax != null && repsMax <= reps) repsMax = null;
            } else if (MAX_REPS_WORD.matcher(repsText.strip()).matches()) {
                maxReps = true;
            } else {
                line.reject("повт", repsText);
            }
        }
        var weightKey = values.containsKey("вес") ? "вес" : "доп_вес";
        Double weight = line.decimal(values.get(weightKey), weightKey, KG, MAX_WEIGHT);
        Double percent = line.decimal(values.get("процент"), "процент", PERCENT, MAX_WEIGHT);
        Double rpe = line.rpe(values.get("rpe"));
        String rir = line.rir(values.get("rir"));
        Integer seconds = line.seconds(values.get("время"), "время", false);
        Integer meters = line.meters(values.get("дистанция"));
        // «Без отдыха» — отдых 0: значение, а не «не сказано».
        Integer rest = line.seconds(values.get("отдых"), "отдых", true);
        String type = null;
        var typeText = values.get("тип");
        if (typeText != null) {
            type = TemplateVocabulary.setType(typeText, scope).orElse(null);
            if (type == null) line.reject("тип", typeText);
        }
        var noteText = note == null || note.toString().isBlank() ? null : note.toString().strip();
        if (reps == null && !maxReps && weight == null && percent == null && rpe == null && rir == null && seconds == null
                && meters == null && noteText == null && type == null && rest == null && values.get("кол") == null) {
            return null;
        }
        return new Values(count, reps, repsMax, maxReps, weight, percent, rpe, rir, seconds, meters, rest, type, noteText);
    }

    /** Значения одной строки подходов: формат и пределы. Неверное — в {@code skipped}, без угадывания. */
    private record Reader(String exercise, List<String> skipped) {
        void reject(String key, String value) { skipped.add(exercise + ": " + key + "=" + value); }

        Integer count(String text, int max) {
            if (text == null) return null;
            var m = COUNT.matcher(text);
            if (!m.matches()) {
                reject("кол", text);
                return null;
            }
            var value = Integer.parseInt(m.group(1));
            return value < 1 || value > max ? null : value;
        }

        Double decimal(String text, String key, Pattern format, double max) {
            if (text == null) return null;
            var m = format.matcher(text.toLowerCase(Locale.ROOT));
            if (!m.matches()) {
                reject(key, text);
                return null;
            }
            var value = parse(m.group(1));
            return value <= 0 || value > max ? null : value;
        }

        Double rpe(String text) {
            if (text == null) return null;
            var m = PLAIN.matcher(text);
            if (!m.matches()) {
                reject("RPE", text);
                return null;
            }
            var value = parse(m.group(1));
            return value < 1 || value > MAX_RPE ? null : value;
        }

        String rir(String text) {
            if (text == null) return null;
            var m = RANGE.matcher(text.strip());
            if (!m.matches()) {
                reject("RIR", text);
                return null;
            }
            return m.group(2) == null ? m.group(1) : m.group(1) + "-" + m.group(2);
        }

        Integer seconds(String text, String key, boolean zero) {
            if (text == null) return null;
            var m = TIME.matcher(text.toLowerCase(Locale.ROOT));
            if (!m.matches()) {
                reject(key, text);
                return null;
            }
            var value = parse(m.group(1));
            var unit = m.group(2);
            int factor = unit.startsWith("мин") ? SECONDS_IN_MINUTE : "ч".equals(unit) ? SECONDS_IN_HOUR : 1;
            var total = (int) Math.round(value * factor);
            return total < 0 || total == 0 && !zero ? null : total;
        }

        Integer meters(String text) {
            if (text == null) return null;
            var m = DISTANCE.matcher(text.toLowerCase(Locale.ROOT));
            if (!m.matches()) {
                reject("дистанция", text);
                return null;
            }
            var total = (int) Math.round(parse(m.group(1)) * ("км".equals(m.group(2)) ? METERS_IN_KILOMETER : 1));
            return total <= 0 ? null : total;
        }
    }

    private static Integer bounded(int value, int max) { return value < 1 || value > max ? null : value; }

    private static double parse(String number) { return Double.parseDouble(number.replace(',', '.')); }
}
