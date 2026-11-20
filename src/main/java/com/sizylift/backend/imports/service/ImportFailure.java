package com.sizylift.backend.imports.service;

import org.springframework.http.HttpStatus;

/** Только безопасные сообщения: исключения провайдера и содержимое программы в ответ не попадают. */
public class ImportFailure extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public ImportFailure(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
    public HttpStatus status() { return status; }
    public String code() { return code; }
    public static ImportFailure invalid(String message) {
        return new ImportFailure(HttpStatus.BAD_REQUEST, "invalid_import", message);
    }
    /** Ссылка не на Google Таблицу (сторонний сайт или документ другого типа). */
    public static ImportFailure notSheetUrl() {
        return new ImportFailure(HttpStatus.BAD_REQUEST, "sheet_url_invalid",
                "Это не ссылка на Google Таблицу. Скопируйте адрес таблицы: он начинается с https://docs.google.com/spreadsheets/.");
    }
    public static ImportFailure conflict() {
        return new ImportFailure(HttpStatus.CONFLICT, "import_conflict", "Импорт изменился. Обновите данные.");
    }
    public static ImportFailure unavailable() {
        return new ImportFailure(HttpStatus.SERVICE_UNAVAILABLE, "import_unavailable", "AI-импорт временно недоступен.");
    }
    public static ImportFailure missing() {
        return new ImportFailure(HttpStatus.NOT_FOUND, "import_not_found", "Импорт не найден.");
    }
}
