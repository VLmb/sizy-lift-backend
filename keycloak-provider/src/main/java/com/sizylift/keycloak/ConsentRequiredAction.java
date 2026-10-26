package com.sizylift.keycloak;

import jakarta.ws.rs.core.Response;
import org.keycloak.authentication.RequiredActionContext;
import org.keycloak.authentication.requiredactions.TermsAndConditions;

/** Запрашивает отсутствующее согласие при браузерном входе, включая SSO и IdP. */
public final class ConsentRequiredAction extends TermsAndConditions {
    @Override public String getId() { return AccountConsent.ACTION_ID; }
    @Override public String getDisplayText() { return "Sizy account consent"; }

    @Override
    public void evaluateTriggers(RequiredActionContext context) {
        var settings = AccountConsent.settings(context.getRealm());
        if (settings.enabled() && !AccountConsent.accepted(settings, context.getUser())) {
            context.getUser().addRequiredAction(getId());
        }
    }

    @Override
    public void requiredActionChallenge(RequiredActionContext context) {
        var settings = AccountConsent.settings(context.getRealm());
        if (!settings.enabled() || AccountConsent.accepted(settings, context.getUser())) {
            context.success();
            return;
        }
        challenge(context, settings, null);
    }

    @Override
    public void processAction(RequiredActionContext context) {
        var settings = AccountConsent.settings(context.getRealm());
        if (!settings.enabled()) { context.success(); return; }
        var data = context.getHttpRequest().getDecodedFormParameters();
        if (data.containsKey("cancel")) { context.failure("sizyConsentDeclined"); return; }
        if (!settings.ready()) { challenge(context, settings, "sizyLegalUnavailable"); return; }
        if (!AccountConsent.wasDisplayed(settings, context.getAuthenticationSession())) {
            challenge(context, settings, "sizyConsentChanged");
            return;
        }
        if (!data.containsKey("accept") || !AccountConsent.checked(data.getFirst(AccountConsent.FIELD))) {
            challenge(context, settings, "sizyConsentRequired");
            return;
        }
        AccountConsent.record(settings, context.getAuthenticationSession(), context.getUser());
        context.success();
    }

    private void challenge(RequiredActionContext context, AccountConsent.Settings settings, String error) {
        var form = AccountConsent.show(settings, context.getAuthenticationSession(), context.form());
        if (error != null) form.setError(error);
        if (!settings.ready()) {
            context.challenge(form.setError("sizyLegalUnavailable").createErrorPage(Response.Status.SERVICE_UNAVAILABLE));
            return;
        }
        context.challenge(form.createForm("sizy-consent.ftl"));
    }
}
