package com.sizylift.backend.imports.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import org.dhatim.fastexcel.Workbook;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Таблица → сетка непустых ячеек для модели (ADR-0008, раздел 2). */
class TableGridTest {

    final TableGrid grid = new TableGrid(ImportTestProperties.defaults());

    @Test
    void csvFollowsRfc4180AndKeepsOnlyNonEmptyCells() {
        var csv = "Упражнение,Подходы,Комментарий\r\n"
                + "\"Присед, со штангой\",5x5,\"сказал \"\"легко\"\"\"\n"
                + ",,\n"
                + "Жим,4x6,\"две\nстроки\"";

        assertThat(grid.fromCsv(csv)).isEqualTo("""
                A1: Упражнение
                B1: Подходы
                C1: Комментарий
                A2: Присед, со штангой
                B2: 5x5
                C2: сказал "легко"
                A4: Жим
                B4: 4x6
                C4: две строки""");
    }

    @Test
    void semicolonSeparatedCsvFromRussianExcelIsRecognised() {
        assertThat(grid.fromCsv("Присед;75%;5\nЖим;70%;6")).isEqualTo("""
                A1: Присед
                B1: 75%
                C1: 5
                A2: Жим
                B2: 70%
                C2: 6""");
    }

    @Test
    void xlsxSheetsBecomeGridsWithCachedFormulaValues() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var workbook = new Workbook(bytes, "test", "1.0")) {
            var week = workbook.newWorksheet("Неделя 1");
            week.value(0, 0, "Присед");
            week.value(0, 1, 0.75);
            week.value(1, 0, "Жим");
            week.value(1, 27, "AB");
            workbook.newWorksheet("Пустой");
        }

        var text = grid.fromXlsx(new ByteArrayInputStream(bytes.toByteArray()));

        assertThat(text).startsWith("Лист: Неделя 1\nA1: Присед\nB1: 0.75\nA2: Жим\nAB2: AB")
                .doesNotContain("Пустой");
    }

    @Test
    void tooManyCellsAreRejected() {
        var csv = new StringBuilder();
        for (int i = 0; i <= ImportTestProperties.MAX_CELLS; i++) {
            csv.append("x\n");
        }

        assertThatThrownBy(() -> grid.fromCsv(csv.toString())).isInstanceOf(ImportException.class)
                .extracting(e -> ((ImportException) e).code()).isEqualTo("too_many_cells");
    }

    @Test
    void brokenXlsxIsAClearError() {
        assertThatThrownBy(() -> grid.fromXlsx(new ByteArrayInputStream(new byte[] {0x50, 0x4B, 3, 4, 0})))
                .isInstanceOf(ImportException.class).extracting(e -> ((ImportException) e).code())
                .isEqualTo("invalid_file");
    }

    @Test
    void columnNamesContinueAfterZ() {
        assertThat(TableGrid.column(0)).isEqualTo("A");
        assertThat(TableGrid.column(25)).isEqualTo("Z");
        assertThat(TableGrid.column(26)).isEqualTo("AA");
        assertThat(TableGrid.column(701)).isEqualTo("ZZ");
        assertThat(TableGrid.column(702)).isEqualTo("AAA");
    }
}
