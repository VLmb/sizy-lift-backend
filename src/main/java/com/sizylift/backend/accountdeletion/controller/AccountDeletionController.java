package com.sizylift.backend.accountdeletion.controller;

import com.sizylift.backend.accountdeletion.service.AccountDeletionService;
import com.sizylift.backend.identity.controller.AuthenticatedAccount;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code DELETE /api/v1/me}: удаление аккаунта и всех данных пользователя без возможности восстановления. */
@RestController
public class AccountDeletionController {

    private final AuthenticatedAccount authenticatedAccount;
    private final AccountDeletionService deletion;

    public AccountDeletionController(AuthenticatedAccount authenticatedAccount, AccountDeletionService deletion) {
        this.authenticatedAccount = authenticatedAccount;
        this.deletion = deletion;
    }

    @DeleteMapping("/api/v1/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAccount(@AuthenticationPrincipal Jwt jwt) {
        deletion.delete(authenticatedAccount.resolve(jwt));
    }
}
