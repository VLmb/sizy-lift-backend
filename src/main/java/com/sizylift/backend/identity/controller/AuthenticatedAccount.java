package com.sizylift.backend.identity.controller;

import com.sizylift.backend.identity.entity.UserAccount;
import com.sizylift.backend.identity.service.ExternalIdentity;
import com.sizylift.backend.identity.service.UserAccountService;
import org.springframework.security.oauth2.core.oidc.StandardClaimNames;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.stereotype.Component;

/** Находит аккаунт Sizy по проверенному access token; используется контроллерами всех фич. */
@Component
public class AuthenticatedAccount {

    private final UserAccountService userAccounts;

    public AuthenticatedAccount(UserAccountService userAccounts) {
        this.userAccounts = userAccounts;
    }

    public UserAccount resolve(Jwt jwt) {
        return userAccounts.resolve(new ExternalIdentity(
                jwt.getClaimAsString(JwtClaimNames.ISS),
                jwt.getSubject(),
                jwt.getClaimAsString(StandardClaimNames.EMAIL),
                Boolean.TRUE.equals(jwt.getClaimAsBoolean(StandardClaimNames.EMAIL_VERIFIED)),
                displayName(jwt)));
    }

    private static String displayName(Jwt jwt) {
        var name = jwt.getClaimAsString(StandardClaimNames.NAME);
        if (name != null && !name.isBlank()) {
            return name.strip();
        }
        var parts = java.util.stream.Stream.of(jwt.getClaimAsString(StandardClaimNames.GIVEN_NAME),
                        jwt.getClaimAsString(StandardClaimNames.FAMILY_NAME))
                .filter(part -> part != null && !part.isBlank())
                .map(String::strip)
                .collect(java.util.stream.Collectors.joining(" "));
        return parts.isEmpty() ? null : parts;
    }
}
