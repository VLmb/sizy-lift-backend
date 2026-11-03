package com.sizylift.backend.imports;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import com.sizylift.backend.imports.entity.ImportSession;
import com.sizylift.backend.imports.entity.ImportSession.SourceItem;
import com.sizylift.backend.imports.source.ImportSourceReader;
import com.sizylift.backend.web.service.SheetImportService;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.FormulaError;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Сравнивает весь извлечённый источник с эталоном до какого-либо LLM-разбора. */
class ImportTranscriptionTest {
    private final SheetImportService sheets = mock(SheetImportService.class);
    private final ImportSourceReader reader = new ImportSourceReader(
            ImportSourceReaderTest.limits(5 * 1024 * 1024, 50 * 1024 * 1024), sheets);

    @ParameterizedTest
    @ValueSource(strings = {"xls", "xlsx"})
    void workbookValuesCoordinatesAndOrderMatchTheCompleteGoldenSource(String extension) throws Exception {
        try (Workbook book = extension.equals("xls") ? new HSSFWorkbook() : new XSSFWorkbook()) {
            var sheet = book.createSheet("Неделя 1");
            sheet.createRow(0).createCell(0).setCellValue("Силовой блок");
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 3));
            var row = sheet.createRow(3);
            row.createCell(0).setCellValue("Жим лёжа");
            row.createCell(1).setCellValue(3);
            row.createCell(2).setCellValue(62.75);
            row.createCell(3).setCellValue(0.725);
            format(book, row.getCell(3), "0.0%");
            row.createCell(4).setCellValue("8–12 повторений\nRIR 2; пауза «внизу»");
            row.createCell(6).setCellFormula("C4*2");
            row.createCell(7).setCellValue(LocalDate.of(2026, 11, 9));
            format(book, row.getCell(7), "dd.mm.yyyy");
            row.createCell(8).setCellValue(90.0 / 86400);
            format(book, row.getCell(8), "[m]:ss");
            row.createCell(9).setCellValue(true);
            row.createCell(10).setCellErrorValue(FormulaError.DIV0.getCode());
            book.createSheet("Приватные заметки").createRow(0).createCell(0).setCellValue("НЕ ПЕРЕДАВАТЬ");
            book.setSheetHidden(1, true);
            book.createSheet("Неделя 2").createRow(2).createCell(2).setCellValue("  Присед с паузой  ");
            book.getCreationHelper().createFormulaEvaluator().evaluateAll();
            var bytes = new ByteArrayOutputStream();
            book.write(bytes);

            assertThat(reader.file("произвольная программа." + extension, bytes.toByteArray()).items()).containsExactly(
                    new SourceItem("sheet:0", "Неделя 1"),
                    new SourceItem("sheet:0!A1", "Силовой блок"),
                    new SourceItem("sheet:0!A4", "Жим лёжа"),
                    new SourceItem("sheet:0!B4", "3"),
                    new SourceItem("sheet:0!C4", "62,75"),
                    new SourceItem("sheet:0!D4", "72,5%"),
                    new SourceItem("sheet:0!E4", "8–12 повторений\nRIR 2; пауза «внизу»"),
                    new SourceItem("sheet:0!G4", "125,5"),
                    new SourceItem("sheet:0!H4", "09.11.2026"),
                    new SourceItem("sheet:0!I4", "1:30"),
                    new SourceItem("sheet:0!J4", "TRUE"),
                    new SourceItem("sheet:0!K4", "#DIV/0!"),
                    new SourceItem("sheet:0!merge:A1:D1", "Объединённые ячейки A1:D1"),
                    new SourceItem("sheet:2", "Неделя 2"),
                    new SourceItem("sheet:2!C3", "  Присед с паузой  "));
        }
    }

    @Test
    void anUncachedFormulaIsMarkedInsteadOfPretendingItsValueIsZero() throws Exception {
        try (var book = new XSSFWorkbook()) {
            book.createSheet("План").createRow(0).createCell(0).setCellFormula("75*2");
            var bytes = new ByteArrayOutputStream();
            book.write(bytes);
            assertThat(reader.file("plan.xlsx", bytes.toByteArray()).items()).containsExactly(
                    new SourceItem("sheet:0", "План"),
                    new SourceItem("sheet:0!A1", "Формула без сохранённого результата: 75*2"));
        }
    }

    @Test
    void csvQuotesDoNotChangeDialectAndBlankRowsKeepTheirCoordinates() {
        var csv = "\uFEFF\"Жим; с паузой\",3,\"62,5\"\r\n\r\n\"Тяга \"\"с пола\"\"\",,\"8–12\nRIR 2\"\r\n";
        assertThat(reader.file("plan.csv", csv.getBytes(StandardCharsets.UTF_8)).items()).containsExactly(
                new SourceItem("csv:1:1", "Жим; с паузой"),
                new SourceItem("csv:1:2", "3"),
                new SourceItem("csv:1:3", "62,5"),
                new SourceItem("csv:3:1", "Тяга \"с пола\""),
                new SourceItem("csv:3:3", "8–12\nRIR 2"));
    }

    @ParameterizedTest
    @ValueSource(strings = {";", "\t"})
    void metadataFirstRowAndWindowsEncodingDoNotRequireATemplate(String delimiter) {
        var csv = "Силовой блок\r\n\r\nПрисед" + delimiter + "3x5" + delimiter + "100,5";
        assertThat(reader.file("plan.csv", csv.getBytes(Charset.forName("windows-1251"))).items()).containsExactly(
                new SourceItem("csv:1:1", "Силовой блок"),
                new SourceItem("csv:3:1", "Присед"),
                new SourceItem("csv:3:2", "3x5"),
                new SourceItem("csv:3:3", "100,5"));
    }

    @Test
    void googleSheetsCsvKeepsEmptyColumnsLineBreaksAndPercentText() {
        var url = "https://docs.google.com/spreadsheets/d/12345678901234567890/edit#gid=42";
        when(sheets.fetch(url)).thenReturn("День,,Упражнение,Нагрузка\r\nПн,,\"Жим, узкий\",72.5%\r\n");
        var session = mock(ImportSession.class);
        when(session.sourceKind()).thenReturn("sheet");
        when(session.sourceText()).thenReturn(url);
        assertThat(reader.read(session).items()).containsExactly(
                new SourceItem("csv:1:1", "День"),
                new SourceItem("csv:1:3", "Упражнение"),
                new SourceItem("csv:1:4", "Нагрузка"),
                new SourceItem("csv:2:1", "Пн"),
                new SourceItem("csv:2:3", "Жим, узкий"),
                new SourceItem("csv:2:4", "72.5%"));
        verify(sheets).fetch(url);
    }

    private void format(Workbook book, Cell cell, String pattern) {
        var style = book.createCellStyle();
        style.setDataFormat(book.createDataFormat().getFormat(pattern));
        cell.setCellStyle(style);
    }
}
