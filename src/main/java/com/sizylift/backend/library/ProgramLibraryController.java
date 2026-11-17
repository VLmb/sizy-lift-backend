package com.sizylift.backend.library;

import com.sizylift.backend.identity.controller.AuthenticatedAccount;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Папки и порядок шаблонов тренера на сайте (раздел «Программы»). */
@RestController
@RequestMapping("/api/v2/web/program-library")
public class ProgramLibraryController {

    private final AuthenticatedAccount accounts;
    private final ProgramLibraryService library;

    public ProgramLibraryController(AuthenticatedAccount accounts, ProgramLibraryService library) {
        this.accounts = accounts;
        this.library = library;
    }

    @GetMapping
    public ProgramLibrary get(@AuthenticationPrincipal Jwt jwt) {
        return library.get(accounts.resolve(jwt).id());
    }

    @PutMapping
    public ProgramLibrary replace(@AuthenticationPrincipal Jwt jwt, @RequestBody ProgramLibrary request) {
        return library.replace(accounts.resolve(jwt).id(), request);
    }
}
