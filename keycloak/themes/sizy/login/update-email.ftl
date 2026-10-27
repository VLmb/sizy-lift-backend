<#-- Based on Keycloak 26.8.1 (Apache-2.0); retains upstream fields and actions. -->
<#import "template.ftl" as layout>
<#import "password-commons.ftl" as passwordCommons>
<#import "user-profile-commons.ftl" as userProfileCommons>
<#import "buttons.ftl" as buttons>
<@layout.registrationLayout displayMessage=messagesPerField.exists('global') displayRequiredFields=false; section>
<!-- template: update-email.ftl -->
    <#if section = "header">
        ${msg("updateEmailTitle")}
    <#elseif section = "form">
        <p class="sizy-intro">${msg("sizyUpdateEmailIntro")}</p>
        <form id="kc-update-email-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post">
            <@userProfileCommons.userProfileFormFields/>

            <div class="${properties.kcFormGroupClass!}">
                <@passwordCommons.logoutOtherSessions/>

                <@buttons.actionGroup horizontal=true>
                    <@buttons.button id="kc-submit" label="sizySave"/>
                    <#if isAppInitiatedAction??>
                        <@buttons.button id="kc-cancel" label="doCancel" type="secondary" name="cancel-aia" value="true"/>
                    </#if>
                </@buttons.actionGroup>
            </div>
        </form>
    </#if>
</@layout.registrationLayout>
