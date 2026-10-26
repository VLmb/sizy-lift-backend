<#macro termsAcceptance>
  <#if termsAcceptanceRequired?? && termsAcceptanceRequired>
    <div class="sizy-consent-group">
      <#if sizyLegalReady!false>
        <div class="sizy-consent-check">
          <input type="checkbox" id="termsAccepted" name="termsAccepted" value="on"
                 aria-describedby="sizy-consent-error" aria-invalid="${messagesPerField.existsError('termsAccepted')?c}">
          <label for="termsAccepted">${msg("sizyConsentPrefix")}
            <a href="${realm.attributes['sizy.legal.consent-url']}" target="_blank" rel="noopener noreferrer" referrerpolicy="no-referrer">${msg("sizyConsentLink")}</a>
          </label>
        </div>
        <p class="sizy-data-notice">${msg("sizySyncNotice")}
          <a href="${realm.attributes['sizy.legal.privacy-url']}" target="_blank" rel="noopener noreferrer" referrerpolicy="no-referrer">${msg("sizyPrivacyPolicy")}</a>.
        </p>
      <#else>
        <p class="sizy-error" role="alert">${msg("sizyLegalUnavailable")}</p>
      </#if>
      <span id="sizy-consent-error" class="sizy-error" aria-live="polite"><#if messagesPerField.existsError('termsAccepted')>${kcSanitize(messagesPerField.get('termsAccepted'))?no_esc}</#if></span>
    </div>
  </#if>
</#macro>
