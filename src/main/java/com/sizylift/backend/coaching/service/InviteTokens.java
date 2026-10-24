package com.sizylift.backend.coaching.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/** Токены приглашений: 128 случайных бит в base64url; в базе только SHA-256. */
@Component
class InviteTokens {

    static final int TOKEN_BYTES = 16;
    private static final String HASH_ALGORITHM = "SHA-256";
    /** 16 байт в base64url без выравнивания — 22 символа. */
    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9_-]{22}");

    private final SecureRandom random = new SecureRandom();

    String generate() {
        var bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    boolean wellFormed(String token) {
        return token != null && FORMAT.matcher(token).matches();
    }

    byte[] hash(String token) {
        try {
            return MessageDigest.getInstance(HASH_ALGORITHM).digest(token.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }
}
