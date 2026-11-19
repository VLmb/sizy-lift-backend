package com.sizylift.backend.imports.template;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import com.sizylift.backend.imports.entity.ImportSession.SourceItem;
import com.sizylift.backend.template.SetLine;

import static com.sizylift.backend.imports.dto.ImportLimits.WEEKS;

/** Явные назначения в таблице. Связывает их с упражнением только по однозначному названию. */
final class SourcePrescriptions {
    private static final Pattern XLS_CELL = Pattern.compile("(sheet:\\d+)!([A-Z]+)(\\d+)");
    private static final Pattern CSV_CELL = Pattern.compile("(csv):(\\d+):(\\d+)");
    private static final Pattern WEEK = Pattern.compile("\\b(?:недел[яиь]|нед\\.?|week|w)\\s*(\\d{1,3})(?:\\s*[-–—]\\s*(\\d{1,3}))?(?!\\d)", Pattern.UNICODE_CHARACTER_CLASS);
    private static final String NUMBER = "\\d+(?:[.,]\\d+)?";
    private static final String RANGE = NUMBER + "(?:\\s*[-–—]\\s*" + NUMBER + ")?";
    private static final Pattern RIR = Pattern.compile("\\brir\\s*[:=]?\\s*(" + RANGE + ")(?![\\d.,]|\\s*(?:→|->))");
    private static final Pattern RPE = Pattern.compile("\\brpe\\s*[:=≤]?\\s*\\d");
    private static final Pattern REST = Pattern.compile("^\\s*(?:отдых|rest)(?:\\s+(?:между подходами|between sets))?(?:\\s*\\(([^()]+)\\))?$");
    private static final Pattern EXPLICIT_REST = Pattern.compile(
            "(?<!\\p{L})(?:отдых\\s*[:=]?\\s*(" + RANGE + "\\s*\\p{L}+)|"
                    + "(?:пауза|перерыв)\\s*[:=]?\\s*(" + RANGE + "\\s*\\p{L}+)\\s+между\\s+(?:подходами|сетами))(?!\\p{L})");
    private static final Pattern INTENSITY = Pattern.compile(
            "(?:целевая\\s+)?интенсивность(?:\\s*\\([^()]+\\))?|(?:целевой\\s+)?(?:rir|rpe)(?:\\s*таргет)?");

    private static final Pattern PERCENT_1RM = Pattern.compile("^(" + NUMBER + ")\\s*%\\s*(?:1\\s*пм|1rm)(?:\\s*\\+.+)?$");
    private static final Pattern SUPERSET = Pattern.compile("(?:суперсет|superset)\\s+([a-zа-я])([12])");

    private static final Targets NONE = new Targets(null, null, null, false, null);
    private final Map<String, List<ExerciseRow>> exercises = new HashMap<>();

    record Targets(Integer restSeconds, String rir, String defaultRir, boolean explicitRpe, String percentOneRm) {
        boolean hasValues() { return restSeconds != null || rir != null || defaultRir != null || percentOneRm != null; }
    }

    private record Header(int exercise, Integer rest, String restUnit, Integer intensity, Integer block, Map<Integer, WeekColumn> weeks,
                          Set<Integer> weeklyColumns) { }
    private record WeekColumn(int column, String rir) { }
    private record ExerciseRow(String sheet, int number, Map<Integer, String> cells, Header header, String globalRir) { }

    SourcePrescriptions(List<SourceItem> source) {
        Map<String, TreeMap<Integer, Map<Integer, String>>> sheets = new HashMap<>();
        for (var item : source) {
            var xls = XLS_CELL.matcher(item.ref());
            if (xls.matches()) {
                add(sheets, xls.group(1), Integer.parseInt(xls.group(3)), column(xls.group(2)), item.text());
                continue;
            }
            var csv = CSV_CELL.matcher(item.ref());
            if (csv.matches()) add(sheets, csv.group(1), Integer.parseInt(csv.group(2)), Integer.parseInt(csv.group(3)), item.text());
        }
        sheets.forEach(this::index);
    }

    Targets forExercise(String name, int week) {
        var row = uniqueRow(name);
        if (row == null) return NONE;
        var header = row.header();
        var weekColumn = header.weeks().get(week);
        String weekly = weekColumn == null ? "" : row.cells().getOrDefault(weekColumn.column(), "");
        String intensity = header.intensity() == null ? "" : row.cells().getOrDefault(header.intensity(), "");

        var rest = explicitRest(List.of(weekly));
        if (rest == null && header.rest() != null) {
            var value = row.cells().get(header.rest());
            rest = SetLine.importRestSeconds(value);
            if (rest == null && value != null && value.strip().matches(RANGE) && !header.restUnit().isEmpty())
                rest = SetLine.importRestSeconds(value + " " + header.restUnit());
        }
        if (rest == null) {
            rest = explicitRest(row.cells().entrySet().stream()
                    .filter(e -> e.getKey() != header.exercise() && !header.weeklyColumns().contains(e.getKey()))
                    .map(Map.Entry::getValue).toList());
        }

        var effort = sourceRir(weekly);
        boolean explicitRpe = RPE.matcher(normalize(weekly)).find();
        if (effort == null && !explicitRpe) {
            effort = sourceRir(intensity);
            explicitRpe = RPE.matcher(normalize(intensity)).find();
        }
        if (effort == null && !explicitRpe && weekColumn != null) effort = weekColumn.rir();
        var percent = PERCENT_1RM.matcher(normalize(intensity));
        return new Targets(rest, effort, row.globalRir(), explicitRpe, percent.matches() ? percent.group(1).replace(',', '.') : null);
    }

    boolean formsSuperset(String firstName, String secondName) {
        var first = uniqueRow(firstName);
        var second = uniqueRow(secondName);
        if (first == null || second == null || first.header().block() == null
                || !first.sheet().equals(second.sheet()) || first.header() != second.header()
                || first.number() + 1 != second.number()) return false;
        var column = first.header().block();
        var left = SUPERSET.matcher(normalize(first.cells().getOrDefault(column, "")));
        var right = SUPERSET.matcher(normalize(second.cells().getOrDefault(column, "")));
        return left.matches() && right.matches() && left.group(1).equals(right.group(1))
                && left.group(2).equals("1") && right.group(2).equals("2");
    }

    private ExerciseRow uniqueRow(String name) {
        var rows = exercises.get(normalize(name));
        return rows == null || rows.size() != 1 ? null : rows.getFirst();
    }

    private void index(String sheet, TreeMap<Integer, Map<Integer, String>> rows) {
        Header header = null;
        var workingRir = new HashSet<String>();
        for (var entry : rows.entrySet()) {
            var cells = entry.getValue();
            var next = header(cells);
            if (next != null) {
                header = next;
                continue;
            }
            if (header == null) {
                for (var cell : cells.entrySet()) {
                    if (normalize(cell.getValue()).matches("рабочая зона|рабочий rir|целевой rir|working rir")) {
                        var value = sourceRir(cells.getOrDefault(cell.getKey() + 1, ""));
                        if (value != null) workingRir.add(value);
                    }
                }
                continue;
            }
            var name = cells.get(header.exercise());
            if (name != null && !name.isBlank()) {
                exercises.computeIfAbsent(normalize(name), ignored -> new ArrayList<>())
                        .add(new ExerciseRow(sheet, entry.getKey(), cells, header, workingRir.size() == 1 ? workingRir.iterator().next() : null));
            }
        }
    }

    private static Header header(Map<Integer, String> cells) {
        Integer exercise = null;
        Integer rest = null;
        Integer intensity = null;
        Integer block = null;
        String restUnit = "";
        Map<Integer, WeekColumn> weeks = new HashMap<>();
        Set<Integer> weeklyColumns = new HashSet<>();
        for (var entry : cells.entrySet()) {
            var text = normalize(entry.getValue());
            if (text.matches("упражнение|название упражнения|exercise|movement")) exercise = entry.getKey();
            var restLabel = REST.matcher(text);
            if (restLabel.matches()) {
                rest = entry.getKey();
                restUnit = restLabel.group(1) == null ? "" : restLabel.group(1);
            }
            if (INTENSITY.matcher(text).matches()) intensity = entry.getKey();
            if (text.matches("блок(?:\\s*/\\s*день)?|группа|block|group")) block = entry.getKey();
            var label = WEEK.matcher(text);
            if (label.lookingAt()) {
                int min = Integer.parseInt(label.group(1));
                int max = label.group(2) == null ? min : Integer.parseInt(label.group(2));
                if (min < 1 || max < min || max > WEEKS) continue;
                weeklyColumns.add(entry.getKey());
                var week = new WeekColumn(entry.getKey(), sourceRir(text));
                for (int number = min; number <= max; number++) {
                    if (weeks.containsKey(number)) weeks.put(number, null);
                    else weeks.put(number, week);
                }
            }
        }
        return exercise == null ? null : new Header(exercise, rest, restUnit, intensity, block, weeks, weeklyColumns);
    }

    private static String sourceRir(String text) {
        var normalized = normalize(text);
        var m = RIR.matcher(normalized);
        if (RPE.matcher(normalized).find() || normalized.contains("→") || normalized.contains("->") || !m.find()) return null;
        var value = m.group(1).replaceAll("\\s+", "").replace('–', '-').replace('—', '-');
        return m.find() || SetLine.rpeFromRir(value) == null ? null : value;
    }

    private static Integer explicitRest(Iterable<String> cells) {
        Integer result = null;
        for (var cell : cells) {
            var m = EXPLICIT_REST.matcher(normalize(cell));
            while (m.find()) {
                var value = SetLine.importRestSeconds(m.group(1) == null ? m.group(2) : m.group(1));
                if (value == null) continue;
                if (result != null && !result.equals(value)) return null;
                result = value;
            }
        }
        return result;
    }

    private static void add(Map<String, TreeMap<Integer, Map<Integer, String>>> sheets,
                            String sheet, int row, int column, String value) {
        sheets.computeIfAbsent(sheet, ignored -> new TreeMap<>()).computeIfAbsent(row, ignored -> new TreeMap<>()).put(column, value);
    }

    private static int column(String text) {
        int value = 0;
        for (int i = 0; i < text.length(); i++) value = value * 26 + text.charAt(i) - 'A' + 1;
        return value;
    }

    private static String normalize(String text) {
        return text.strip().toLowerCase(Locale.ROOT).replace('ё', 'е').replace(' ', ' ').replaceAll("\\s+", " ");
    }
}
