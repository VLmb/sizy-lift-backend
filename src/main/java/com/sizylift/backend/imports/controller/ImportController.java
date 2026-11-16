package com.sizylift.backend.imports.controller;

import java.io.IOException;
import java.util.UUID;

import com.sizylift.backend.identity.controller.AuthenticatedAccount;
import com.sizylift.backend.imports.dto.ImportDtos.*;
import com.sizylift.backend.imports.service.ImportCommitService;
import com.sizylift.backend.imports.service.ImportEvents;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.sizylift.backend.imports.service.ImportFailure;
import com.sizylift.backend.imports.service.ImportService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v2/imports")
public class ImportController {
    private final AuthenticatedAccount accounts;
    private final ImportService imports;
    private final ImportCommitService commits;
    private final ImportEvents events;

    public ImportController(AuthenticatedAccount accounts, ImportService imports, ImportCommitService commits,
                            ImportEvents events) {
        this.accounts = accounts; this.imports = imports; this.commits = commits; this.events = events;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Created create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Create input) {
        return imports.create(accounts.resolve(jwt).id(), input, null, null);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Created upload(@AuthenticationPrincipal Jwt jwt, @Valid @RequestPart("metadata") Create input,
                           @RequestPart("file") MultipartFile file) throws IOException {
        if (!input.source().equals("file")) throw ImportFailure.invalid("Для загрузки файла укажите source=file.");
        return imports.create(accounts.resolve(jwt).id(), input, file.getBytes(), file.getOriginalFilename());
    }

    /** Незавершённые импорты пользователя, новые сверху: карточки «вернуться к разбору». */
    @GetMapping
    public java.util.List<Summary> list(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) UUID ownerId) {
        return imports.list(accounts.resolve(jwt).id(), ownerId);
    }

    @GetMapping("/{id}")
    public View get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) { return imports.get(accounts.resolve(jwt).id(), id); }

    /**
     * Поток состояния сессии (Server-Sent Events): событие {@code state} с View — сразу и при каждом изменении.
     * Закрывается, когда разбор закончен; опрос GET остаётся запасным путём.
     */
    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> events(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        var emitter = events.subscribe(accounts.resolve(jwt).id(), id);
        // Прокси не должны буферизовать поток (nginx — X-Accel-Buffering), кэш ответа не нужен.
        return ResponseEntity.ok().header("X-Accel-Buffering", "no").header("Cache-Control", "no-cache").body(emitter);
    }

    @PostMapping("/{id}/clarifications")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public View answer(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody Answer input) {
        return imports.answer(accounts.resolve(jwt).id(), id, input);
    }

    @PostMapping("/{id}/sheets")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public View selectSheets(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody SelectSheets input) {
        return imports.selectSheets(accounts.resolve(jwt).id(), id, input);
    }

    @PostMapping("/{id}/weeks/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public View retryWeeks(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody RetryWeeks input) {
        return imports.retryWeeks(accounts.resolve(jwt).id(), id, input);
    }

    @PatchMapping("/{id}")
    public View patch(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody Patch input) {
        return imports.patch(accounts.resolve(jwt).id(), id, input);
    }

    @PostMapping("/{id}/commit")
    public Receipt commit(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody Commit input) {
        return commits.commit(accounts.resolve(jwt).id(), id, input);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) { imports.cancel(accounts.resolve(jwt).id(), id); }

    @ExceptionHandler(ImportFailure.class)
    ResponseEntity<Failure> failure(ImportFailure failure) {
        // Тип задан явно: запрос потока событий ждёт text/event-stream, а ошибка до начала потока — обычный JSON.
        return ResponseEntity.status(failure.status()).contentType(MediaType.APPLICATION_JSON)
                .body(new Failure(failure.code(), failure.getMessage()));
    }
}
