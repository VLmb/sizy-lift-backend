package com.sizylift.backend.imports.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.sizylift.backend.imports.entity.ImportSession.SourceItem;
import com.sizylift.backend.imports.model.ProgramParserModel.Input;
import com.sizylift.backend.imports.model.ProgramParserModel.Task;

/**
 * Сообщение пользователя для модели: язык интерфейса, источник, ответы на вопросы, задача. Источник и ответы
 * одинаковы у всех вызовов сессии, задача — в конце, поэтому общий префикс запроса попадает в кэш провайдера.
 * Таблицы выводятся сеткой: строка с буквами столбцов, затем строки с номером в начале — модель видит расположение
 * ячеек, а координата не повторяется в каждой ячейке. Переносы и табуляции внутри значений экранируются:
 * значение не может начать строку раздела.
 */
public final class ModelInputRenderer {
    private static final Pattern SHEET_TITLE = Pattern.compile("sheet:(\\d+)");
    private static final Pattern SHEET_CELL = Pattern.compile("(sheet:\\d+)!([A-Z]+)(\\d+)");
    private static final Pattern CSV_CELL = Pattern.compile("csv:(\\d+):(\\d+)");
    private static final String CSV = "csv";
    private static final int ALPHABET = 26;
    private static final int INITIAL_CAPACITY = 4096;
    /**
     * О чём рассуждать в первом вызове: структура источника решает качество всех частей, а сопоставление с каталогом
     * сервер проверяет сам. Без этой подсказки модель тратит рассуждения на каталог и пропускает правила.
     */
    static final String THINK = "Если рассуждаешь — коротко, не больше 15 строк, и только о структуре источника: где программа, где шаблон дней и где "
            + "недели, как связаны таблицы, что меняется от недели к неделе, какие указания относятся ко всем тренировкам. "
            + "Итог рассуждений запиши строками «Правило:». Не обдумывай упражнения и не составляй ответ в рассуждениях — "
            + "сразу пиши его: упражнения с каталогом сопоставляй без перебора, сомнительное сервер покажет пользователю.\n";

    private ModelInputRenderer() { }

    public static String render(Input input) {
        var text = new StringBuilder(INITIAL_CAPACITY);
        text.append("ЯЗЫК ИНТЕРФЕЙСА\n").append(input.source().language()).append('\n');
        text.append("ИСТОЧНИК\n");
        source(text, input.source().items());
        if (!input.history().isEmpty()) {
            text.append("ОТВЕТЫ ПОЛЬЗОВАТЕЛЯ\n");
            for (var answer : input.history()) {
                text.append("Вопрос: ").append(escape(answer.question())).append('\n')
                        .append("Ответ: ").append(escape(answer.answer())).append('\n');
            }
        }
        task(text, input.task());
        if (input.repairMessage() != null && !input.repairMessage().isBlank())
            for (var line : ("ОШИБКИ ПРЕДЫДУЩЕГО ОТВЕТА\n" + input.repairMessage()).split("\n"))
                text.append(escape(line)).append('\n');
        return text.toString();
    }

    /** Строки текста — как есть; лист или CSV — сеткой непустых строк и столбцов, объединения — после сетки. */
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
        for (var item : loose) text.append(escape(item.text())).append('\n');
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
            if (csv) text.append("CSV\n");
            else {
                var number = SHEET_TITLE.matcher(name);
                int index = number.matches() ? Integer.parseInt(number.group(1)) + 1 : 1;
                text.append("ЛИСТ ").append(index).append(": ").append(escape(title)).append('\n');
            }
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
            for (var item : extra) text.append("объединение ").append(item.ref().substring(item.ref().indexOf('!') + 1))
                    .append('\t').append(escape(item.text())).append('\n');
        }
    }

    private static void task(StringBuilder text, Task task) {
        text.append("ЗАДАЧА\n");
        switch (task.kind()) {
            case PLAN -> text.append(THINK).append("Напиши строку «Программа», раздел «Упражнения» и раздел «План» с правилами и всеми неделями. ")
                    .append("Если тренировок во всей программе не больше ").append(task.singleCallWorkouts())
                    .append(", затем напиши «Тренировки:» и распиши все недели подробно. Иначе закончи ответ после плана — ")
                    .append("недели распишут отдельно. Если без ответа пользователя не разобрать — только строку «Вопрос:».\n");
            case PLAN_ONLY -> text.append(THINK).append("Напиши только строку «Программа», раздел «Упражнения» и раздел «План» с правилами и всеми неделями, ")
                    .append("без «Тренировки». Если без ответа пользователя не разобрать — только строку «Вопрос:».\n");
            case WEEKS -> {
                text.append("Распиши подробно только недели ")
                        .append(task.weeks().stream().map(String::valueOf).collect(Collectors.joining(", ")))
                        .append(": начни со строки «Тренировки:». Не пиши «Программа» и «План», вопросов не задавай — ")
                        .append("неясное сохрани в заметке или комментарии.\nПлан этих недель:\n");
                for (var week : task.plan()) {
                    text.append("Неделя: ").append(week.week());
                    if (!week.cycle().isEmpty()) text.append(" | цикл=").append(escape(week.cycle()));
                    if (!week.days().isEmpty()) text.append(" | дни=").append(escape(String.join(",", week.days())));
                    text.append('\n');
                }
                if (!task.rules().isEmpty()) {
                    text.append("Правила чтения этой программы — соблюдай их во всех неделях:\n");
                    for (var rule : task.rules()) text.append("Правило: ").append(escape(rule)).append('\n');
                }
                text.append("Упражнения программы — в «Упражнение:» пиши название ровно как здесь:\n");
                for (var name : task.exercises()) text.append("- ").append(escape(name)).append('\n');
                text.append("Если в этих неделях есть упражнение, которого нет в списке, перед «Тренировки:» напиши ")
                        .append("«Упражнения:» и строки только для новых упражнений.\n");
            }
        }
    }

    static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\r\n", "\n").replace('\r', '\n')
                .replace("\n", "\\n").replace("\t", "\\t");
    }
}
