package com.sizylift.backend.imports.service;

import org.springframework.http.HttpStatus;

/** Понятная пользователю ошибка импорта: HTTP-статус, код и текст (docs/imports.md, «Ошибки»). */
public class ImportException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ImportException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    static ImportException invalidInput(String code, String message) {
        return new ImportException(HttpStatus.BAD_REQUEST, code, message);
    }
}
