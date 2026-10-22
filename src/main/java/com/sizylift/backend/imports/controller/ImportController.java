package com.sizylift.backend.imports.controller;

import java.io.IOException;
import java.time.LocalDate;
import java.util.UUID;

import com.sizylift.backend.identity.controller.AuthenticatedAccount;
import com.sizylift.backend.imports.dto.ImportDtos.AnswersRequest;
import com.sizylift.backend.imports.dto.ImportDtos.ImportResponse;
import com.sizylift.backend.imports.dto.ImportDtos.StartRequest;
import com.sizylift.backend.imports.service.ImportException;
import com.sizylift.backend.imports.service.ImportService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** ИИ-импорт программ (docs/imports.md). Ответ — вопросы или черновик; сервер программу не сохраняет. */
@RestController
@RequestMapping("/api/v2/imports")
public class ImportController {

    private final AuthenticatedAccount authenticatedAccount;
    private final ImportService imports;

    public ImportController(AuthenticatedAccount authenticatedAccount, ImportService imports) {
        this.authenticatedAccount = authenticatedAccount;
        this.imports = imports;
    }

    /** Текст или ссылка на Google Таблицу — ровно одно из двух. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ImportResponse start(@AuthenticationPrincipal Jwt jwt, @RequestBody StartRequest request) {
        var userId = userId(jwt);
        if ((request.text() == null) == (request.url() == null)) {
            throw new ImportException(HttpStatus.BAD_REQUEST, "invalid_source", "Передайте либо text, либо url");
        }
        return request.text() != null
                ? imports.importText(userId, request.text(), request.startDate(), request.targetOwnerId())
                : imports.importSheet(userId, request.url(), request.startDate(), request.targetOwnerId());
    }

    /** Файл {@code .xlsx} или {@code .csv} до 1 МБ. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportResponse startFile(@AuthenticationPrincipal Jwt jwt, @RequestPart("file") MultipartFile file,
                                    @RequestParam(required = false) LocalDate startDate,
                                    @RequestParam(required = false) UUID targetOwnerId) throws IOException {
        return imports.importFile(userId(jwt), file.getBytes(), startDate, targetOwnerId);
    }

    @PostMapping("/{sessionId}/answers")
    public ImportResponse answer(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID sessionId,
                                 @Valid @RequestBody AnswersRequest request) {
        return imports.answer(userId(jwt), sessionId, request.answers());
    }

    private UUID userId(Jwt jwt) {
        return authenticatedAccount.resolve(jwt).id();
    }
}
