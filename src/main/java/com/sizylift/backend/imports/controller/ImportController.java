package com.sizylift.backend.imports.controller;

import java.io.IOException;
import java.util.UUID;

import com.sizylift.backend.identity.controller.AuthenticatedAccount;
import com.sizylift.backend.imports.dto.ImportDtos.*;
import com.sizylift.backend.imports.service.ImportCommitService;
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

    public ImportController(AuthenticatedAccount accounts, ImportService imports, ImportCommitService commits) {
        this.accounts = accounts; this.imports = imports; this.commits = commits;
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

    @GetMapping("/{id}")
    public View get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) { return imports.get(accounts.resolve(jwt).id(), id); }

    @PostMapping("/{id}/clarifications")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public View answer(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody Answer input) {
        return imports.answer(accounts.resolve(jwt).id(), id, input);
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
        return ResponseEntity.status(failure.status()).body(new Failure(failure.code(), failure.getMessage()));
    }
}
