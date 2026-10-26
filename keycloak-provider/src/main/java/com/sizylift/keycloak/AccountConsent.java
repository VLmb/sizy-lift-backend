package com.sizylift.keycloak;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.ArrayList;

import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.keycloak.util.JsonSerialization;

/** Версия согласия берётся только из серверной конфигурации realm. */
final class AccountConsent {
    static final String ACTION_ID = "sizy-account-consent";
    static final String FIELD = "termsAccepted";
    static final String ENABLED = "sizy.legal.enabled";
    static final String VERSION = "sizy.legal.version";
    static final String PRIVACY_URL = "sizy.legal.privacy-url";
    static final String CONSENT_URL = "sizy.legal.consent-url";
    static final String RECEIPT_ATTRIBUTE = "sizy_account_consent";
    private static final String DISPLAYED_NOTE = "sizy.account-consent.displayed";
    private static final String ACCEPTED_VALUE = "on";

    private AccountConsent() { }

    static Settings settings(RealmModel realm) {
        return new Settings(Boolean.parseBoolean(realm.getAttribute(ENABLED)),
                value(realm, VERSION), value(realm, PRIVACY_URL), value(realm, CONSENT_URL));
    }

    private static String value(RealmModel realm, String key) {
        var value = realm.getAttribute(key);
        return value == null ? "" : value.trim();
    }

    static boolean checked(String value) {
        return ACCEPTED_VALUE.equals(value);
    }

    static boolean wasDisplayed(Settings settings, AuthenticationSessionModel session) {
        return settings.ready() && settings.key().equals(session.getAuthNote(DISPLAYED_NOTE));
    }

    static LoginFormsProvider show(Settings settings, AuthenticationSessionModel session, LoginFormsProvider form) {
        if (settings.ready()) session.setAuthNote(DISPLAYED_NOTE, settings.key());
        else session.removeAuthNote(DISPLAYED_NOTE);
        return form.setAttribute("termsAcceptanceRequired", settings.enabled())
                .setAttribute("sizyLegalReady", settings.ready())
                .setAttribute("sizyConsentVersion", settings.version());
    }

    static boolean accepted(Settings settings, UserModel user) {
        return settings.ready() && user.getAttributeStream(RECEIPT_ATTRIBUTE).anyMatch(value -> matches(value, settings.key()));
    }

    private static boolean matches(String value, String key) {
        try {
            return key.equals(JsonSerialization.readValue(value, Map.class).get("configuration"));
        } catch (java.io.IOException | IllegalArgumentException exception) {
            return false;
        }
    }

    static void record(Settings settings, AuthenticationSessionModel session, UserModel user) {
        if (!wasDisplayed(settings, session)) throw new IllegalStateException("Consent configuration changed");
        if (accepted(settings, user)) return;
        try {
            var receipt = JsonSerialization.writeValueAsString(Map.of(
                    "configuration", settings.key(),
                    "version", settings.version(), "acceptedAt", Instant.now().toString(),
                    "privacyUrl", settings.privacyUrl(), "consentUrl", settings.consentUrl()));
            var receipts = new ArrayList<>(user.getAttributeStream(RECEIPT_ATTRIBUTE).toList());
            receipts.add(receipt);
            user.setAttribute(RECEIPT_ATTRIBUTE, receipts);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Cannot store consent receipt", exception);
        }
        session.removeAuthNote(DISPLAYED_NOTE);
    }

    record Settings(boolean enabled, String version, String privacyUrl, String consentUrl) {
        boolean ready() {
            return enabled && version.matches("[a-zA-Z0-9._-]{1,80}")
                    && publicHttpsUrl(privacyUrl) && publicHttpsUrl(consentUrl);
        }

        String key() {
            try {
                var digest = MessageDigest.getInstance("SHA-256");
                var bytes = (version + "\n" + privacyUrl + "\n" + consentUrl).getBytes(StandardCharsets.UTF_8);
                return HexFormat.of().formatHex(digest.digest(bytes));
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException(exception);
            }
        }

        private static boolean publicHttpsUrl(String value) {
            try {
                var uri = URI.create(value);
                return "https".equals(uri.getScheme()) && uri.getHost() != null
                        && uri.getUserInfo() == null && uri.getFragment() == null;
            } catch (IllegalArgumentException exception) {
                return false;
            }
        }
    }
}
