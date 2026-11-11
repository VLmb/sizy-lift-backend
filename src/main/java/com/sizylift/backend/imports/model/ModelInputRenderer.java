package com.sizylift.backend.imports.model;

import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.sizylift.backend.imports.entity.ImportSession.SourceItem;
import com.sizylift.backend.imports.model.ProgramParserModel.Input;
import com.sizylift.backend.imports.model.ProgramParserModel.Task;

/**
 * Вход модели — текст, а не JSON: меньше токенов. Каталог идёт первым, раздел ЗАДАЧА — последним, поэтому
 * префикс запроса совпадает между импортами и между вызовами одной сессии и попадает в кэш провайдера.
 * Таблицы выводятся сеткой: модель видит расположение ячеек, а координата не повторяется в каждой ячейке.
 * Переносы и табуляции внутри значений экранируются: значение не может начать строку раздела или подделать ref.
 */
public final class ModelInputRenderer {
    public static final String PLAN_TOOL = "emit_program_plan";
    public static final String WEEKS_TOOL = "emit_program_weeks";
    private static final Locale RUSSIAN = Locale.forLanguageTag("ru-RU");
    private static final Pattern SHEET_TITLE = Pattern.compile("(sheet:\\d+)");
    private static final Pattern SHEET_CELL = Pattern.compile("(sheet:\\d+)!([A-Z]+)(\\d+)");
    private static final Pattern CSV_CELL = Pattern.compile("csv:(\\d+):(\\d+)");
    private static final String CSV = "csv";
    private static final int ALPHABET = 26;
    private static final int INITIAL_CAPACITY = 4096;

    private ModelInputRenderer() { }

    public static String render(Input input) {
        var text = new StringBuilder(INITIAL_CAPACITY).append("КАТАЛОГ\n");
        for (var entry : input.catalog()) text.append(entry.alias()).append('\t').append(escape(entry.name())).append('\n');
        text.append("ЯЗЫК ИНТЕРФЕЙСА\n").append(input.source().language()).append('\n');
        text.append("ДАТА СТАРТА\n").append(input.startDate()).append(", ")
                .append(input.startDate().getDayOfWeek().getDisplayName(TextStyle.FULL, RUSSIAN)).append('\n');
        text.append("ИСТОЧНИК\n");
        source(text, input.source().items());
        if (!input.history().isEmpty()) {
            text.append("ОТВЕТЫ ПОЛЬЗОВАТЕЛЯ\n");
            for (int i = 0; i < input.history().size(); i++) {
                var answer = input.history().get(i);
                text.append("answer:").append(i + 1).append("\tВопрос: ").append(escape(answer.question()))
                        .append("\tОтвет: ").append(escape(answer.answer())).append('\n');
            }
        }
        task(text, input.task());
        if (input.repairMessage() != null && !input.repairMessage().isBlank())
            for (var line : ("ОШИБКИ ПРЕДЫДУЩЕГО ОТВЕТА\n" + input.repairMessage()).split("\n"))
                text.append(escape(line)).append('\n');
        return text.toString();
    }

    /**
     * Строки текста — {@code text:N<TAB>строка}. Лист или CSV — заголовок с правилом ref, строка столбцов и строки
     * с номером в начале; выводятся только непустые строки и столбцы. Прочие ref, например объединения ячеек,
     * идут списком после сетки своего листа.
     */
    private static void source(StringBuilder text, List<SourceItem> items) {
        var grids = new LinkedHashMap<String, Grid>();
        var loose = new ArrayList<SourceItem>();
        for (var item : items) {
            var cell = SHEET_CELL.matcher(item.ref());
            var csv = CSV_CELL.matcher(item.ref());
            if (cell.matches()) {
                grid(grids, cell.group(1), "").put(Integer.parseInt(cell.group(3)), column(cell.group(2)), item.text());
            } else if (csv.matches()) {
                grid(grids, CSV, "").put(Integer.parseInt(csv.group(1)), Integer.parseInt(csv.group(2)), item.text());
            } else if (SHEET_TITLE.matcher(item.ref()).matches()) {
                grid(grids, item.ref(), item.text());
            } else if (item.ref().startsWith("sheet:")) {
                var sheet = item.ref().substring(0, item.ref().indexOf('!'));
                grid(grids, sheet, "").extra.add(item);
            } else loose.add(item);
        }
        for (var item : loose) text.append(item.ref()).append('\t').append(escape(item.text())).append('\n');
        grids.forEach((name, grid) -> grid.render(text, name));
    }

    private static Grid grid(Map<String, Grid> grids, String name, String title) {
        var grid = grids.computeIfAbsent(name, key -> new Grid());
        if (!title.isEmpty()) grid.title = title;
        return grid;
    }

    /** A → 1, Z → 26, AA → 27. */
    private static int column(String letters) {
        int result = 0;
        for (int i = 0; i < letters.length(); i++) result = result * ALPHABET + (letters.charAt(i) - 'A' + 1);
        return result;
    }

    private static String letters(int column) {
        var result = new StringBuilder();
        for (int value = column; value > 0; value = (value - 1) / ALPHABET)
            result.insert(0, (char) ('A' + (value - 1) % ALPHABET));
        return result.toString();
    }

    private static final class Grid {
        private final TreeMap<Integer, TreeMap<Integer, String>> rows = new TreeMap<>();
        private final List<SourceItem> extra = new ArrayList<>();
        private String title = "";

        void put(int row, int column, String value) { rows.computeIfAbsent(row, key -> new TreeMap<>()).put(column, value); }

        void render(StringBuilder text, String name) {
            boolean csv = name.equals(CSV);
            if (csv) text.append("CSV, ref ячейки: csv:строка:столбец, например csv:3:2\n");
            else text.append("ЛИСТ ").append(name).append('\t').append(escape(title)).append(", ref ячейки: ")
                    .append(name).append("!столбецстрока, например ").append(name).append("!B3\n");
            var columns = new TreeSet<Integer>();
            rows.values().forEach(row -> columns.addAll(row.keySet()));
            if (!columns.isEmpty()) {
                text.append(columns.stream().map(column -> csv ? String.valueOf(column) : letters(column))
                        .collect(Collectors.joining("\t", "\t", "\n")));
                rows.forEach((number, row) -> {
                    text.append(number);
                    for (var column : columns) text.append('\t').append(escape(row.getOrDefault(column, "")));
                    text.append('\n');
                });
            }
            for (var item : extra) text.append(item.ref()).append('\t').append(escape(item.text())).append('\n');
        }
    }

    private static void task(StringBuilder text, Task task) {
        text.append("ЗАДАЧА\n");
        switch (task.kind()) {
            case PLAN -> text.append(PLAN_TOOL).append(". Если тренировок во всей программе не больше ")
                    .append(task.singleCallWorkouts()).append(", заполни и weeks подробно; иначе weeks=[].\n");
            case PLAN_ONLY -> text.append(PLAN_TOOL).append(". Только план: weeks=[].\n");
            case WEEKS -> {
                text.append(WEEKS_TOOL).append(". Разбери подробно недели: ")
                        .append(task.weeks().stream().map(String::valueOf).collect(Collectors.joining(", ")))
                        .append(".\nПлан этих недель (week<TAB>cycle<TAB>name<TAB>workouts):\n");
                for (var week : task.plan()) text.append(week.week()).append('\t').append(escape(week.cycle())).append('\t')
                        .append(escape(week.name())).append('\t').append(week.workouts()).append('\n');
                text.append("Упражнения программы (id<TAB>name):\n");
                for (var exercise : task.exercises())
                    text.append(exercise.key()).append('\t').append(escape(exercise.originalName())).append('\n');
            }
        }
    }

    static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\r\n", "\n").replace('\r', '\n')
                .replace("\n", "\\n").replace("\t", "\\t");
    }
}
