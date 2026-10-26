package com.sizylift.backend.web.controller;

import com.sizylift.backend.identity.controller.AuthenticatedAccount;
import com.sizylift.backend.web.service.SheetImportService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2/web/import")
public class SheetImportController {
    private final AuthenticatedAccount accounts;
    private final SheetImportService sheets;

    public SheetImportController(AuthenticatedAccount accounts, SheetImportService sheets) {
        this.accounts = accounts;
        this.sheets = sheets;
    }

    public record SheetRequest(@NotBlank @Size(max = 500) String url) {
    }

    public record SheetResponse(String text) {
    }

    @PostMapping("/sheet")
    public SheetResponse sheet(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SheetRequest request) {
        accounts.resolve(jwt);
        return new SheetResponse(sheets.fetch(request.url()));
    }
}
