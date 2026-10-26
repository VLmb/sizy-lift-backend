package com.sizylift.backend.web.controller;

import java.util.List;
import java.util.UUID;

import com.sizylift.backend.identity.controller.AuthenticatedAccount;
import com.sizylift.backend.web.repository.ProgramArchiveRepository.ArchivedProgram;
import com.sizylift.backend.web.service.ProgramArchiveService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2/web/programs")
public class ProgramArchiveController {
    private final AuthenticatedAccount accounts;
    private final ProgramArchiveService archive;

    public ProgramArchiveController(AuthenticatedAccount accounts, ProgramArchiveService archive) {
        this.accounts = accounts;
        this.archive = archive;
    }

    public record ArchiveRequest(@Min(1) long revision) {
    }

    @GetMapping("/archive")
    public List<ArchivedProgram> list(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) UUID ownerId) {
        return archive.list(accounts.resolve(jwt).id(), ownerId);
    }

    @PostMapping("/{id}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archive(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                        @RequestParam(required = false) UUID ownerId, @Valid @RequestBody ArchiveRequest request) {
        archive.archive(accounts.resolve(jwt).id(), ownerId, id, request.revision());
    }

    @PostMapping("/{id}/restore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void restore(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                        @RequestParam(required = false) UUID ownerId) {
        archive.restore(accounts.resolve(jwt).id(), ownerId, id);
    }
}
