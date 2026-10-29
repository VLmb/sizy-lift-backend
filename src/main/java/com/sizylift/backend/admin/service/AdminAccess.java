package com.sizylift.backend.admin.service;

import java.util.Collection;
import java.util.Map;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

public final class AdminAccess {
    public static final String ROLE = "sizy-admin";
    public static final String CLIENT = "sizylift-admin";

    private AdminAccess() {
    }

    public static boolean allowed(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token)) return false;
        Object realm = token.getToken().getClaim("realm_access");
        return CLIENT.equals(token.getToken().getClaimAsString("azp"))
                && realm instanceof Map<?, ?> claims
                && claims.get("roles") instanceof Collection<?> roles && roles.contains(ROLE);
    }
}
