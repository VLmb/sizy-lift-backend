package com.sizylift.backend.web.controller;

import com.sizylift.backend.identity.controller.AuthenticatedAccount;
import com.sizylift.backend.web.dto.AssignDtos.AssignRequest;
import com.sizylift.backend.web.dto.AssignDtos.AssignResponse;
import com.sizylift.backend.web.service.ProgramAssignService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Назначить программу нескольким подопечным (CR-13). */
@RestController
public class ProgramAssignController {

    private final AuthenticatedAccount accounts;
    private final ProgramAssignService assign;

    public ProgramAssignController(AuthenticatedAccount accounts, ProgramAssignService assign) {
        this.accounts = accounts;
        this.assign = assign;
    }

    @PostMapping("/api/v2/web/programs/assign")
    @ResponseStatus(HttpStatus.CREATED)
    public AssignResponse assign(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AssignRequest request) {
        return assign.assign(accounts.resolve(jwt).id(), request);
    }
}
