package com.sizylift.backend.imports.service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import com.sizylift.backend.imports.config.ImportProperties;
import org.dhatim.fastexcel.reader.Cell;
import org.dhatim.fastexcel.reader.CellType;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.springframework.stereotype.Component;

/**
 * Таблица → компактная сетка для модели: только непустые ячейки, по строке на ячейку ({@code A1: значение}),
 * лист начинается заголовком. Из {@code .xlsx} берутся сохранённые значения формул: проценты и веса уже
 * посчитаны в таблице (ADR-0008, раздел 1).
 */
@Component
class TableGrid {

    static final String SHEET_HEADER = "Лист: ";
    private static final char QUOTE = '"';
    private static final char COMMA = ',';
    private static final char SEMICOLON = ';';
    private static final int ALPHABET = 26;

    private final ImportProperties properties;

    TableGrid(ImportProperties properties) {
        this.properties = properties;
    }

    String fromCsv(String csv) {
        var cells = new ArrayList<String>();
        var rows = parseCsv(csv);
        for (int row = 0; row < rows.size(); row++) {
            for (int column = 0; column < rows.get(row).size(); column++) {
                add(cells, row, column, rows.get(row).get(column));
            }
        }
        return String.join("\n", cells);
    }

    String fromXlsx(InputStream input) {
        var lines = new ArrayList<String>();
        var cellCount = new int[1];
        try (var workbook = new ReadableWorkbook(input)) {
            var sheets = workbook.getSheets().limit(properties.maxSheets()).toList();
            for (var sheet : sheets) {
                var cells = new ArrayList<String>();
                try (var rows = sheet.openStream()) {
                    rows.forEach(row -> row.forEach(cell -> {
                        if (cell != null && cell.getType() != CellType.EMPTY && cell.getType() != CellType.ERROR) {
                            add(cells, row.getRowNum() - 1, cell.getColumnIndex(), text(cell));
                        }
                    }));
                }
                if (!cells.isEmpty()) {
                    cellCount[0] += cells.size();
                    lines.add(SHEET_HEADER + sheet.getName());
                    lines.addAll(cells);
                }
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof ImportException importException) {
                throw importException;
            }
            throw ImportException.invalidInput("invalid_file", "Не удалось прочитать файл .xlsx");
        }
        return String.join("\n", lines);
    }

    private void add(List<String> cells, int row, int column, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (cells.size() >= properties.maxCells()) {
            throw ImportException.invalidInput("too_many_cells",
                    "В таблице больше " + properties.maxCells() + " заполненных ячеек");
        }
        cells.add(column(column) + (row + 1) + ": " + value.strip().replace('\n', ' '));
    }

    private static String text(Cell cell) {
        var text = cell.getText();
        return text == null || text.isBlank() ? cell.getRawValue() : text;
    }

    /** 0 → A, 25 → Z, 26 → AA. */
    static String column(int index) {
        var name = new StringBuilder();
        for (int value = index + 1; value > 0; value = (value - 1) / ALPHABET) {
            name.insert(0, (char) ('A' + (value - 1) % ALPHABET));
        }
        return name.toString();
    }

    /** RFC 4180: кавычки, удвоенные кавычки, переводы строк в кавычках; разделитель — запятая или точка с запятой. */
    static List<List<String>> parseCsv(String csv) {
        var separator = detectSeparator(csv);
        var rows = new ArrayList<List<String>>();
        var row = new ArrayList<String>();
        var field = new StringBuilder();
        var quoted = false;
        for (int i = 0; i < csv.length(); i++) {
            var c = csv.charAt(i);
            if (quoted) {
                if (c == QUOTE && i + 1 < csv.length() && csv.charAt(i + 1) == QUOTE) {
                    field.append(QUOTE);
                    i++;
                } else if (c == QUOTE) {
                    quoted = false;
                } else {
                    field.append(c);
                }
            } else if (c == QUOTE) {
                quoted = true;
            } else if (c == separator) {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < csv.length() && csv.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        if (!field.isEmpty() || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }

    /** Excel в русской локали сохраняет CSV через точку с запятой. */
    private static char detectSeparator(String csv) {
        var firstLine = csv.lines().findFirst().orElse("");
        return firstLine.chars().filter(c -> c == SEMICOLON).count()
                > firstLine.chars().filter(c -> c == COMMA).count() ? SEMICOLON : COMMA;
    }
}
