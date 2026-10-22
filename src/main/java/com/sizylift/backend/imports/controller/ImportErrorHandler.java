package com.sizylift.backend.imports.controller;

import com.sizylift.backend.imports.dto.ImportDtos.ErrorResponse;
import com.sizylift.backend.imports.service.ImportException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Ошибки импорта — понятные коды и тексты для пользователя (docs/imports.md). */
@RestControllerAdvice(assignableTypes = ImportController.class)
class ImportErrorHandler {

    @ExceptionHandler(ImportException.class)
    ResponseEntity<ErrorResponse> handle(ImportException e) {
        return ResponseEntity.status(e.status()).body(new ErrorResponse(e.code(), e.getMessage()));
    }
}
