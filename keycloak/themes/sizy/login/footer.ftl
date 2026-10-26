<#macro content>
  <footer class="sizy-footer">
    <#assign privacyUrl = (realm.attributes['sizy.legal.privacy-url'])!''>
    <#if privacyUrl?starts_with('https://')>
      <a href="${privacyUrl}" target="_blank" rel="noopener noreferrer" referrerpolicy="no-referrer">${msg("sizyPrivacyPolicy")}</a>
    </#if>
    <span>${msg("sizyBrandFooter")}</span>
  </footer>
</#macro>
