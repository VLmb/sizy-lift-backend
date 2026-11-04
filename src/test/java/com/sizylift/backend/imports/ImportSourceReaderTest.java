package com.sizylift.backend.imports;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.sizylift.backend.imports.config.ImportProperties;
import com.sizylift.backend.imports.service.ImportFailure;
import com.sizylift.backend.imports.source.ImportSourceReader;
import com.sizylift.backend.web.service.SheetImportService;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ImportSourceReaderTest {
    private final ImportSourceReader reader = new ImportSourceReader(limits(5 * 1024 * 1024, 50 * 1024 * 1024), mock(SheetImportService.class));

    @Test
    void xlsxPreservesSheetNamesMergedRangesAndDisplayedPercentages() throws Exception {
        try (var book = new XSSFWorkbook()) {
            var sheet = book.createSheet("Первая неделя");
            var row = sheet.createRow(0);
            row.createCell(0).setCellValue("Присед");
            var percentage = row.createCell(1);
            percentage.setCellValue(0.75);
            var style = book.createCellStyle();
            style.setDataFormat(book.createDataFormat().getFormat("0%"));
            percentage.setCellStyle(style);
            sheet.createRow(1).createCell(0).setCellValue("Заголовок");
            sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, 2));
            book.createSheet("Вторая неделя").createRow(0).createCell(0).setCellValue("Жим");
            var source = reader.file("plan.xlsx", bytes(book));
            assertThat(source.items()).anyMatch(item -> item.text().equals("Первая неделя"));
            assertThat(source.items()).anyMatch(item -> item.ref().equals("sheet:0!B1") && item.text().equals("75%"));
            assertThat(source.items()).anyMatch(item -> item.ref().equals("sheet:0!merge:A2:C2"));
            assertThat(source.items()).anyMatch(item -> item.ref().equals("sheet:1!A1") && item.text().equals("Жим"));
        }
    }

    @Test
    void readsLegacyXlsAndRejectsAnExtensionThatDisagreesWithTheSignature() throws Exception {
        try (var book = new HSSFWorkbook()) {
            book.createSheet("План").createRow(0).createCell(0).setCellValue("3×5");
            var bytes = bytes(book);
            assertThat(reader.file("plan.xls", bytes).items()).anyMatch(item -> item.text().equals("3×5"));
            assertThatThrownBy(() -> reader.file("plan.xlsx", bytes)).isInstanceOf(ImportFailure.class);
        }
    }

    @Test
    void csvNeedsNoKnownHeadersAndPreservesQuotedDelimitersAndLineBreaks() {
        var source = reader.file("plan.csv", "\"Тяга; с паузой\";\"3×5\n100 кг\"\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(source.items()).hasSize(2);
        assertThat(source.items().getFirst().text()).isEqualTo("Тяга; с паузой");
        assertThat(source.items().getLast().text()).contains("3×5\n100 кг");
    }

    @Test
    void rejectsACompressedBombBeforeConstructingTheWorkbook() throws Exception {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
            zip.write(new byte[4096]);
            zip.closeEntry();
        }
        var small = new ImportSourceReader(limits(1024, 1024), mock(SheetImportService.class));
        assertThatThrownBy(() -> small.file("bomb.xlsx", output.toByteArray()))
                .isInstanceOf(ImportFailure.class).hasMessageContaining("Распакованная");
    }

    @Test
    void aLongUnstructuredParagraphDoesNotRequireLineBreaks() {
        assertThat(reader.text("a".repeat(5000)).items().getFirst().text()).hasSize(5000);
        assertThatThrownBy(() -> reader.text("a".repeat(200001)))
                .isInstanceOf(ImportFailure.class).hasMessageContaining("большой");
    }

    static ImportProperties limits(int file, int expanded) {
        return new ImportProperties(false, false, Duration.ofHours(24), Duration.ofMinutes(10), 20, 2, 4, 3, 2,
                file, expanded, 10, 20000, 200000, 1000000, 32768, 300000, 1200000, 8, 12, 8, 8);
    }
    private byte[] bytes(Workbook book) throws Exception {
        var output = new ByteArrayOutputStream();
        book.write(output);
        return output.toByteArray();
    }
}
