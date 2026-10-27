<#-- Based on Keycloak 26.8.1 (Apache-2.0); retains upstream fields and actions. -->
<#import "template.ftl" as layout>
<#import "user-profile-commons.ftl" as userProfileCommons>
<#import "buttons.ftl" as buttons>
<@layout.registrationLayout displayMessage=messagesPerField.exists('global') displayRequiredFields=false; section>
<!-- template: login-update-profile.ftl -->
    <#if section = "header">
        ${msg("loginProfileTitle")}
    <#elseif section = "form">
        <p class="sizy-intro">${msg("sizyUpdateProfileIntro")}</p>
        <form id="kc-update-profile-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post">

            <@userProfileCommons.userProfileFormFields/>

            <@buttons.actionGroup horizontal=true>
                <@buttons.button id="kc-submit" label="sizySave"/>
                <#if isAppInitiatedAction??>
                    <@buttons.button id="kc-cancel" label="doCancel" type="secondary" name="cancel-aia" value="true"/>
                </#if>
            </@buttons.actionGroup>
        </form>
    </#if>
</@layout.registrationLayout>
