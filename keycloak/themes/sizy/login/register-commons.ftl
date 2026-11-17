<#macro termsAcceptance>
  <#if sizyTermsRequired!false>
    <div class="sizy-consent-group">
      <div class="sizy-consent-check">
        <input type="checkbox" id="sizyTermsAccepted" name="sizyTermsAccepted" value="on"
               aria-describedby="sizy-terms-error" aria-invalid="${messagesPerField.existsError('sizyTermsAccepted')?c}">
        <label for="sizyTermsAccepted">${msg("sizyTermsPrefix")}
          <a href="${sizyTermsUrl}" target="_blank" rel="noopener noreferrer" referrerpolicy="no-referrer">${msg("sizyTerms")}</a>
        </label>
      </div>
      <p class="sizy-data-notice">${msg("sizyPrivacyNotice")}
        <a href="${sizyPrivacyUrl}" target="_blank" rel="noopener noreferrer" referrerpolicy="no-referrer">${msg("sizyPrivacyPolicy")}</a>.
      </p>
      <span id="sizy-terms-error" class="sizy-error" aria-live="polite"><#if messagesPerField.existsError('sizyTermsAccepted')>${kcSanitize(messagesPerField.get('sizyTermsAccepted'))?no_esc}</#if></span>
    </div>
  </#if>
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
