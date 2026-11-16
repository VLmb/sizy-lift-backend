package com.sizylift.backend.imports.source;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import com.sizylift.backend.imports.dto.ImportDtos.Sheet;
import com.sizylift.backend.imports.entity.ImportSession.Source;
import com.sizylift.backend.imports.entity.ImportSession.SourceItem;

/**
 * Листы книги в нормализованном источнике: ref листа — {@code sheet:N}, ячейки — {@code sheet:N!B3}. Книга с
 * несколькими непустыми листами ждёт выбора листов пользователем, и в модель уходят только выбранные: в одном
 * файле тренера бывают программы разных людей.
 */
public final class SourceSheets {
    private static final Pattern SHEET = Pattern.compile("sheet:(\\d+)");
    private static final Pattern CELL = Pattern.compile("sheet:(\\d+)!([A-Z]+)(\\d+)");
    private static final int PREVIEW_ROWS = 4;
    private static final int PREVIEW_CHARS = 120;
    private static final String CELL_SEPARATOR = " · ";

    private SourceSheets() { }

    /** Непустые листы по порядку: номер, название, число заполненных ячеек и первые строки. */
    public static List<Sheet> list(Source source) {
        var names = new LinkedHashMap<Integer, String>();
        var rows = new LinkedHashMap<Integer, TreeMap<Integer, List<String>>>();
        var cells = new LinkedHashMap<Integer, Integer>();
        for (var item : source.items()) {
            var sheet = SHEET.matcher(item.ref());
            if (sheet.matches()) {
                names.put(Integer.parseInt(sheet.group(1)), item.text());
                continue;
            }
            var cell = CELL.matcher(item.ref());
            if (!cell.matches()) continue;
            int index = Integer.parseInt(cell.group(1));
            cells.merge(index, 1, Integer::sum);
            rows.computeIfAbsent(index, key -> new TreeMap<>())
                    .computeIfAbsent(Integer.parseInt(cell.group(3)), key -> new ArrayList<>()).add(item.text());
        }
        var result = new ArrayList<Sheet>();
        for (var entry : names.entrySet()) {
            var count = cells.getOrDefault(entry.getKey(), 0);
            if (count == 0) continue;
            result.add(new Sheet(entry.getKey(), entry.getValue(), count, preview(rows.get(entry.getKey()))));
        }
        return result;
    }

    /** Источнику нужен выбор листов: в нём больше одного непустого листа. */
    public static boolean needsSelection(Source source) { return list(source).size() > 1; }

    /** Только выбранные листы; ячейки, объединения и названия остальных листов удаляются. */
    public static Source select(Source source, Set<Integer> selected) {
        var items = new ArrayList<SourceItem>();
        for (var item : source.items()) {
            var index = sheet(item.ref());
            if (index == null || selected.contains(index)) items.add(item);
        }
        return new Source(List.copyOf(items), source.language());
    }

    private static Integer sheet(String ref) {
        if (!ref.startsWith("sheet:")) return null;
        int end = ref.indexOf('!');
        var number = end < 0 ? ref.substring("sheet:".length()) : ref.substring("sheet:".length(), end);
        try { return Integer.valueOf(number); }
        catch (NumberFormatException e) { return null; }
    }

    private static List<String> preview(Map<Integer, List<String>> rows) {
        return rows.values().stream().limit(PREVIEW_ROWS).map(values -> {
            var line = String.join(CELL_SEPARATOR, values).replaceAll("\\s+", " ").strip();
            return line.length() <= PREVIEW_CHARS ? line : line.substring(0, PREVIEW_CHARS - 1) + "…";
        }).toList();
    }
}
