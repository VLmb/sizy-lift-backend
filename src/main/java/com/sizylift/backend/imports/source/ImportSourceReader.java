package com.sizylift.backend.imports.source;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipInputStream;

import com.sizylift.backend.imports.config.ImportProperties;
import com.sizylift.backend.imports.entity.ImportSession;
import com.sizylift.backend.imports.entity.ImportSession.Source;
import com.sizylift.backend.imports.entity.ImportSession.SourceItem;
import com.sizylift.backend.imports.service.ImportFailure;
import com.sizylift.backend.web.service.SheetImportService;
import org.apache.commons.csv.CSVFormat;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.poifs.filesystem.FileMagic;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

/** Извлекает значения и координаты. Колонки, упражнения и структуру программы не интерпретирует. */
@Component
public class ImportSourceReader {
    private static final int MAX_ROWS = 2000;
    private static final int MAX_COLUMNS = 256;
    private static final int MAX_CELL_CHARS = 4000;
    private static final int MAX_ZIP_ENTRIES = 2048;
    private static final int BUFFER_BYTES = 8192;
    private static final int DIALECT_SAMPLE_ROWS = 20;
    private final ImportProperties limits;
    private final SheetImportService sheets;

    public ImportSourceReader(ImportProperties limits, SheetImportService sheets) {
        this.limits = limits;
        this.sheets = sheets;
    }

    public Source read(ImportSession session) {
        return switch (session.sourceKind()) {
            case "text" -> text(session.sourceText());
            case "sheet" -> csv(sheets.fetch(session.sourceText()));
            case "file" -> file(session.sourceName(), session.sourceBytes());
            default -> throw ImportFailure.invalid("Неизвестный тип источника.");
        };
    }

    public Source text(String text) {
        var result = new ArrayList<SourceItem>();
        var lines = text.replace("\uFEFF", "").split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].isBlank()) result.add(new SourceItem("text:" + (i + 1), lines[i]));
        }
        return bounded(result, false);
    }

    public Source file(String name, byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > limits.maxFileBytes())
            throw ImportFailure.invalid("Пустой или слишком большой файл.");
        var lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".csv")) return csv(decode(bytes));
        try {
            var magic = FileMagic.valueOf(bytes);
            if (lower.endsWith(".xlsx") && magic == FileMagic.OOXML) {
                checkZip(bytes);
            } else if (!(lower.endsWith(".xls") && magic == FileMagic.OLE2)) {
                throw ImportFailure.invalid("Содержимое файла не соответствует формату XLS/XLSX/CSV.");
            }
            return workbook(bytes);
        } catch (EncryptedDocumentException e) {
            throw ImportFailure.invalid("Снимите пароль с таблицы и загрузите её снова.");
        } catch (IOException | IllegalArgumentException e) {
            throw ImportFailure.invalid("Не удалось прочитать таблицу. Проверьте файл.");
        }
    }

    private Source workbook(byte[] bytes) throws IOException {
        try (var book = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            if (book.getNumberOfSheets() > limits.maxSheets())
                throw ImportFailure.invalid("В книге слишком много листов.");
            var result = new ArrayList<SourceItem>();
            var formatter = new DataFormatter(Locale.forLanguageTag("ru-RU"));
            formatter.setUseCachedValuesForFormulaCells(true);
            for (int s = 0; s < book.getNumberOfSheets(); s++) {
                if (book.isSheetHidden(s) || book.isSheetVeryHidden(s)) continue;
                var sheet = book.getSheetAt(s);
                var prefix = "sheet:" + s;
                result.add(new SourceItem(prefix, sheet.getSheetName()));
                if (sheet.getLastRowNum() >= MAX_ROWS) throw ImportFailure.invalid("В листе больше 2000 строк.");
                for (var row : sheet) {
                    if (row.getLastCellNum() > MAX_COLUMNS) throw ImportFailure.invalid("В листе слишком много столбцов.");
                    for (var cell : row) {
                        var value = formatter.formatCellValue(cell);
                        if (cell instanceof org.apache.poi.xssf.usermodel.XSSFCell xmlCell
                                && cell.getCellType() == org.apache.poi.ss.usermodel.CellType.FORMULA
                                && (xmlCell.getRawValue() == null || xmlCell.getRawValue().isBlank()))
                            value = "Формула без сохранённого результата: " + cell.getCellFormula();
                        if (!value.isBlank()) {
                            result.add(new SourceItem(prefix + "!" + cell.getAddress().formatAsString(), value));
                            checkSize(result);
                        }
                    }
                }
                if (sheet.getNumMergedRegions() > limits.maxCells())
                    throw ImportFailure.invalid("Слишком много объединённых ячеек.");
                for (var merge : sheet.getMergedRegions()) {
                    result.add(new SourceItem(prefix + "!merge:" + merge.formatAsString(),
                            "Объединённые ячейки " + merge.formatAsString()));
                    checkSize(result);
                }
            }
            return bounded(result);
        }
    }

    private Source csv(String text) {
        if (text.length() > limits.maxSourceChars()) throw ImportFailure.invalid("Источник слишком большой.");
        char delimiter = delimiter(text);
        var format = CSVFormat.RFC4180.builder().setDelimiter(delimiter).setIgnoreEmptyLines(false).get();
        var result = new ArrayList<SourceItem>();
        try (var parser = format.parse(new StringReader(text.replace("\uFEFF", "")))) {
            for (var row : parser) {
                if (row.getRecordNumber() > MAX_ROWS || row.size() > MAX_COLUMNS)
                    throw ImportFailure.invalid("В таблице слишком много строк или столбцов.");
                for (int c = 0; c < row.size(); c++) {
                    if (!row.get(c).isBlank()) {
                        result.add(new SourceItem("csv:" + row.getRecordNumber() + ":" + (c + 1), row.get(c)));
                        checkSize(result);
                    }
                }
            }
        } catch (IOException | java.io.UncheckedIOException e) {
            throw ImportFailure.invalid("Не удалось прочитать CSV.");
        }
        return bounded(result);
    }

    /** Разделители внутри кавычек и переносы внутри ячейки не влияют на выбор CSV-диалекта. */
    private char delimiter(String text) {
        var candidates = new char[] {',', ';', '\t'};
        var counts = new int[candidates.length];
        var rows = new int[candidates.length];
        var current = new int[candidates.length];
        boolean quoted = false;
        int sampled = 0;
        for (int i = 0; i <= text.length() && sampled < DIALECT_SAMPLE_ROWS; i++) {
            char value = i == text.length() ? '\n' : text.charAt(i);
            if (value == '"') {
                if (quoted && i + 1 < text.length() && text.charAt(i + 1) == '"') i++;
                else quoted = !quoted;
            } else if (!quoted) {
                if (value == '\n') {
                    for (int c = 0; c < candidates.length; c++) {
                        counts[c] += current[c];
                        if (current[c] > 0) rows[c]++;
                        current[c] = 0;
                    }
                    sampled++;
                } else {
                    for (int c = 0; c < candidates.length; c++) if (value == candidates[c]) current[c]++;
                }
            }
        }
        int best = 0;
        for (int c = 1; c < candidates.length; c++)
            if (rows[c] > rows[best] || rows[c] == rows[best] && counts[c] > counts[best]) best = c;
        return candidates[best];
    }

    private void checkZip(byte[] bytes) throws IOException {
        long expanded = 0;
        int entries = 0;
        var buffer = new byte[BUFFER_BYTES];
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            while (zip.getNextEntry() != null) {
                if (++entries > MAX_ZIP_ENTRIES) throw ImportFailure.invalid("Слишком сложный файл XLSX.");
                int count;
                while ((count = zip.read(buffer)) != -1) {
                    expanded += count;
                    if (expanded > limits.maxExpandedBytes())
                        throw ImportFailure.invalid("Распакованная таблица слишком большая.");
                }
            }
        }
    }

    private Source bounded(List<SourceItem> items) {
        return bounded(items, true);
    }

    private Source bounded(List<SourceItem> items, boolean limitCellSize) {
        if (items.size() > limits.maxCells()) throw ImportFailure.invalid("В источнике слишком много строк или ячеек.");
        if (limitCellSize && items.stream().anyMatch(item -> item.text().length() > MAX_CELL_CHARS))
            throw ImportFailure.invalid("В строке или ячейке больше 4000 символов.");
        if (items.isEmpty()) throw ImportFailure.invalid("В источнике нет данных.");
        long chars = items.stream().mapToLong(item -> item.text().length() + item.ref().length()).sum();
        if (chars > limits.maxSourceChars()) throw ImportFailure.invalid("Источник слишком большой. Выберите нужные листы.");
        return new Source(List.copyOf(items));
    }

    private void checkSize(List<SourceItem> items) {
        if (items.size() > limits.maxCells()) throw ImportFailure.invalid("В источнике слишком много ячеек.");
        if (!items.isEmpty() && items.getLast().text().length() > MAX_CELL_CHARS)
            throw ImportFailure.invalid("В строке или ячейке больше 4000 символов.");
    }

    private String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, Charset.forName("windows-1251"));
        }
    }
}
