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
    private static final double MIN_IMPORT_RPE = 5;
    private static final int SECONDS_IN_MINUTE = 60;
    private static final int SECONDS_IN_HOUR = 3600;
    private static final int METERS_IN_KILOMETER = 1000;
    private static final Pattern PAIR = Pattern.compile("^([а-яёa-z_]+)\\s*=\\s*(.+)$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final String NUMBER = "(\\d+(?:[.,]\\d+)?)";
    private static final Pattern COUNT = Pattern.compile("^(\\d{1,3})$");
    private static final Pattern VOICE_COUNT = Pattern.compile("^(\\d{1,3})(?:\\s*[-–—]\\s*(\\d{1,3}))?$");
    private static final Pattern REPS = Pattern.compile("^(\\d{1,4})(?:\\s*[-–—]\\s*(\\d{1,4}))?$");
    private static final Pattern MAX_REPS_WORD = Pattern.compile("^(макс|максимум|amrap|до отказа)$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final String EMPTY_BAR_NOTE = "пустой гриф";
    private static final Pattern EMPTY_BAR = Pattern.compile("пустой гриф|(?:с )?пустым грифом",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern KG = Pattern.compile("^" + NUMBER + "\\s*(?:кг)?$");
    private static final Pattern PERCENT = Pattern.compile("^" + NUMBER + "\\s*%?$");
    private static final Pattern PLAIN = Pattern.compile("^" + NUMBER + "$");
    private static final Pattern RANGE = Pattern.compile("^" + NUMBER + "(?:\\s*[-–—]\\s*" + NUMBER + ")?$");
    private static final String REST_UNIT = "(с|сек(?:унд[аы]?)?|мин(?:ут[аы]?)?|ч(?:ас(?:а|ов)?)?|s|sec(?:ond[s]?)?|min(?:ute[s]?)?|h|hour[s]?)";
    private static final Pattern IMPORT_REST = Pattern.compile("^" + NUMBER + "\\s*(?:" + REST_UNIT
            + "\\s*)?(?:[-–—]\\s*" + NUMBER + "\\s*)?" + REST_UNIT + "\\.?$");
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
        return parse(body, resultType, scope, label, skipped, issues, new java.util.ArrayList<>());
    }

    /** То же; {@code rejected} получает отклонённые пары «ключ=значение» как есть — их можно сохранить заметкой. */
    public static Values parse(String body, String resultType, Scope scope, String label, List<String> skipped, List<String> issues,
                               List<String> rejected) {
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
        var line = new Reader(label, skipped, rejected);
        Integer count = line.count(values.get("кол"), scope);
        Integer reps = null;
        Integer repsMax = null;
        boolean maxReps = false;
        var repsText = values.get("повт");
        if (repsText != null) {
            var m = REPS.matcher(repsText);
            if (m.matches()) {
                reps = bounded(Integer.parseInt(m.group(1)), MAX_REPS);
                repsMax = m.group(2) == null ? null : bounded(Integer.parseInt(m.group(2)), MAX_REPS);
                if (scope == Scope.VOICE && m.group(2) != null
                        && (reps == null || repsMax == null || repsMax < reps)) {
                    line.reject("повт", repsText);
                    reps = null;
                    repsMax = null;
                } else if (reps != null && repsMax != null && repsMax <= reps) {
                    repsMax = null;
                }
            } else if (MAX_REPS_WORD.matcher(repsText.strip()).matches()) {
                maxReps = true;
            } else {
                line.reject("повт", repsText);
            }
        }
        var weightKey = values.containsKey("вес") ? "вес" : "доп_вес";
        var weightText = values.get(weightKey);
        boolean emptyBar = scope == Scope.VOICE && weightText != null && EMPTY_BAR.matcher(weightText).matches();
        Double weight = emptyBar ? null : line.decimal(weightText, weightKey, KG, MAX_WEIGHT);
        Double percent = line.decimal(values.get("процент"), "процент", PERCENT, MAX_WEIGHT);
        Double rpe = line.rpe(values.get("rpe"));
        String rir = line.rir(values.get("rir"));
        Integer seconds = line.seconds(values.get("время"), "время", false);
        Integer meters = line.meters(values.get("дистанция"));
        // «Без отдыха» — отдых 0: значение, а не «не сказано».
        Integer rest = scope == Scope.IMPORT ? line.importRest(values.get("отдых")) : line.seconds(values.get("отдых"), "отдых", true);
        String type = null;
        var typeText = values.get("тип");
        if (typeText != null) {
            type = TemplateVocabulary.setType(typeText, scope).orElse(null);
            if (type == null) line.reject("тип", typeText);
        }
        var noteText = note == null || note.toString().isBlank() ? null : note.toString().strip();
        if (emptyBar && (noteText == null || !EMPTY_BAR.matcher(noteText).matches()))
            noteText = noteText == null ? EMPTY_BAR_NOTE : noteText + " · " + EMPTY_BAR_NOTE;
        if (reps == null && !maxReps && weight == null && percent == null && rpe == null && rir == null && seconds == null
                && meters == null && noteText == null && type == null && rest == null && values.get("кол") == null) {
            return null;
        }
        return new Values(count, reps, repsMax, maxReps, weight, percent, rpe, rir, seconds, meters, rest, type, noteText);
    }

    /** Значения одной строки подходов: формат и пределы. Неверное — в {@code skipped}, без угадывания. */
    private record Reader(String exercise, List<String> skipped, List<String> rejected) {
        void reject(String key, String value) {
            skipped.add(exercise + ": " + key + "=" + value);
            rejected.add(key + " " + value);
        }

        Integer count(String text, Scope scope) {
            if (text == null) return null;
            boolean voice = scope == Scope.VOICE;
            var m = (voice ? VOICE_COUNT : COUNT).matcher(text);
            if (!m.matches()) {
                reject("кол", text);
                return null;
            }
            int lower = Integer.parseInt(m.group(1));
            if (!voice || m.group(2) == null) return bounded(lower, scope.maxSets());

            int upper = Integer.parseInt(m.group(2));
            int mean = (int) Math.round((lower + upper) / 2.0);
            if (lower < 1 || upper < lower || mean > scope.maxSets()) {
                reject("кол", text);
                return null;
            }
            return mean;
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

        Integer importRest(String text) {
            if (text == null) return null;
            var value = importRestSeconds(text);
            if (value == null) reject("отдых", text);
            return value;
        }

        Integer seconds(String text, String key, boolean zero) {
            if (text == null) return null;
            var m = TIME.matcher(text.toLowerCase(Locale.ROOT));
            if (!m.matches()) {
                reject(key, text);
                return null;
            }
            var value = parse(m.group(1));
            var total = (int) Math.round(value * timeFactor(m.group(2)));
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

    /** Средний отдых по исходному диапазону, в целых секундах. */
    public static Integer importRestSeconds(String text) {
        if (text == null) return null;
        var clean = text.strip().toLowerCase(Locale.ROOT);
        if (clean.equals("без отдыха") || clean.equals("no rest")) return 0;
        var m = IMPORT_REST.matcher(clean);
        if (!m.matches() || (m.group(2) != null && m.group(3) == null)) return null;
        double lower = parse(m.group(1)) * timeFactor(m.group(2) == null ? m.group(4) : m.group(2));
        double upper = m.group(3) == null ? lower : parse(m.group(3)) * timeFactor(m.group(4));
        if (!Double.isFinite(upper) || upper < lower) return null;
        long seconds = Math.round(lower + (upper - lower) / 2);
        return seconds <= Integer.MAX_VALUE / 1000 ? (int) seconds : null;
    }

    private static int timeFactor(String unit) {
        if (unit.startsWith("мин") || unit.startsWith("min")) return SECONDS_IN_MINUTE;
        return unit.startsWith("ч") || unit.startsWith("h") ? SECONDS_IN_HOUR : 1;
    }

    /** Переводит исходный RIR в поддерживаемый RPE, округляя среднее к ближайшим 0,5. */
    public static Double rpeFromRir(String rir) {
        if (rir == null) return null;
        var m = RANGE.matcher(rir.strip().replace('—', '-'));
        if (!m.matches()) return null;
        double lower = parse(m.group(1));
        double upper = m.group(2) == null ? lower : parse(m.group(2));
        if (upper < lower || MAX_RPE - upper < MIN_IMPORT_RPE) return null;
        return Math.round((MAX_RPE - (lower + upper) / 2) * 2) / 2.0;
    }

    private static Integer bounded(int value, int max) { return value < 1 || value > max ? null : value; }

    private static double parse(String number) { return Double.parseDouble(number.replace(',', '.')); }
}
