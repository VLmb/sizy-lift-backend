package com.sizylift.keycloak;

import java.util.List;

import org.keycloak.authentication.FormContext;
import org.keycloak.authentication.ValidationContext;
import org.keycloak.authentication.forms.RegistrationTermsAndConditions;
import org.keycloak.events.Errors;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.models.utils.FormMessage;

/** Проверка отдельной галочки и запись подтверждения в транзакции регистрации. */
public final class RegistrationConsent extends RegistrationTermsAndConditions {
    public static final String PROVIDER_ID = "sizy-registration-consent";

    @Override public String getId() { return PROVIDER_ID; }
    @Override public String getDisplayType() { return "Sizy account consent"; }
    @Override public String getHelpText() { return "Validates and records versioned account consent."; }

    @Override
    public void buildPage(FormContext context, LoginFormsProvider form) {
        var settings = AccountConsent.settings(context.getRealm());
        if (settings.enabled()) AccountConsent.show(settings, context.getAuthenticationSession(), form);
    }

    @Override
    public void validate(ValidationContext context) {
        var settings = AccountConsent.settings(context.getRealm());
        if (!settings.enabled()) { context.success(); return; }
        var data = context.getHttpRequest().getDecodedFormParameters();
        String error = null;
        if (!settings.ready()) error = "sizyLegalUnavailable";
        else if (!AccountConsent.wasDisplayed(settings, context.getAuthenticationSession())) error = "sizyConsentChanged";
        else if (!AccountConsent.checked(data.getFirst(AccountConsent.FIELD))) error = "sizyConsentRequired";
        if (error == null) { context.success(); return; }
        context.error(Errors.INVALID_REGISTRATION);
        context.validationError(data, List.of(new FormMessage(AccountConsent.FIELD, error)));
    }

    @Override
    public void success(FormContext context) {
        var settings = AccountConsent.settings(context.getRealm());
        if (settings.enabled()) AccountConsent.record(settings, context.getAuthenticationSession(), context.getUser());
    }
}
