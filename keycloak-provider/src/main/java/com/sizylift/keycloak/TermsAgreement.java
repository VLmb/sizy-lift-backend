package com.sizylift.keycloak;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.models.UserModel;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.keycloak.util.JsonSerialization;

/** Фиксирует отдельное принятие пользовательского соглашения. */
final class TermsAgreement {
    static final String FIELD = "sizyTermsAccepted";
    static final String URL = "https://sizylab.ru/legal/2026-11-16/terms.html";
    static final String PRIVACY_URL = "https://sizylab.ru/legal/2026-11-24/privacy.html";
    static final String VERSION = "2026-11-16";
    private static final String RECEIPT = "sizy_terms_acceptance";
    private static final String DISPLAYED = "sizy.terms.displayed";

    private TermsAgreement() { }

    static void show(AuthenticationSessionModel session, LoginFormsProvider form) {
        session.setAuthNote(DISPLAYED, VERSION);
        form.setAttribute("sizyTermsRequired", true).setAttribute("sizyTermsUrl", URL)
                .setAttribute("sizyPrivacyUrl", PRIVACY_URL);
    }

    static boolean displayed(AuthenticationSessionModel session) {
        return VERSION.equals(session.getAuthNote(DISPLAYED));
    }

    static boolean accepted(UserModel user) {
        return user.getAttributeStream(RECEIPT).anyMatch(value -> {
            try {
                return VERSION.equals(JsonSerialization.readValue(value, Map.class).get("version"));
            } catch (IOException | IllegalArgumentException exception) {
                return false;
            }
        });
    }

    static void record(AuthenticationSessionModel session, UserModel user) {
        if (!displayed(session)) throw new IllegalStateException("Terms version was not displayed");
        if (accepted(user)) return;
        try {
            user.setSingleAttribute(RECEIPT, JsonSerialization.writeValueAsString(Map.of(
                    "version", VERSION, "url", URL, "acceptedAt", Instant.now().toString())));
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot store terms receipt", exception);
        }
        session.removeAuthNote(DISPLAYED);
    }
}
