<#import "template.ftl" as layout>
<#import "register-commons.ftl" as consent>
<@layout.registrationLayout; section>
  <#if section = "header">
    ${msg("sizyConsentTitle")}
  <#elseif section = "form">
    <p class="sizy-intro">${msg("sizyConsentIntro")}</p>
    <form id="sizy-consent-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post">
      <@consent.termsAcceptance/>
      <button type="submit" name="accept" value="on" class="${properties.kcButtonPrimaryClass!}">${msg("sizyConsentContinue")}</button>
      <button type="submit" name="cancel" value="on" class="${properties.kcButtonSecondaryClass!}" formnovalidate>${msg("sizyConsentCancel")}</button>
    </form>
    <p class="sizy-local-note">${msg("sizyLocalDiary")}</p>
  </#if>
</@layout.registrationLayout>
