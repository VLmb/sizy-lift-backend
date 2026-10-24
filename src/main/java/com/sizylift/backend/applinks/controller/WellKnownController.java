package com.sizylift.backend.applinks.controller;

import java.util.List;
import java.util.Map;

import com.sizylift.backend.applinks.config.AppLinksProperties;
import com.sizylift.backend.applinks.dto.AppLinksDtos.AppLinks;
import com.sizylift.backend.applinks.dto.AppLinksDtos.AppLinksDetail;
import com.sizylift.backend.applinks.dto.AppLinksDtos.AppleAppSiteAssociation;
import com.sizylift.backend.applinks.dto.AppLinksDtos.AssetLink;
import com.sizylift.backend.applinks.dto.AppLinksDtos.AssetTarget;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Связь домена API с приложением: iOS (Universal Links) и Android (App Links) открывают
 * {@code https://<домен>/invite/<токен>} сразу в установленном приложении. Публичные, без токена.
 */
@RestController
public class WellKnownController {

    static final String APPLE_PATH = "/.well-known/apple-app-site-association";
    static final String ANDROID_PATH = "/.well-known/assetlinks.json";
    /** Пути сайта, которые открывает приложение; совпадают с {@link InvitePageController}. */
    static final String INVITE_PATHS = "/invite/*";
    private static final String APPLE_PATH_KEY = "/";
    private static final String ANDROID_RELATION = "delegate_permission/common.handle_all_urls";
    private static final String ANDROID_NAMESPACE = "android_app";

    private final AppLinksProperties properties;

    public WellKnownController(AppLinksProperties properties) {
        this.properties = properties;
    }

    /** Apple требует {@code application/json} без перенаправлений и без расширения в пути. */
    @GetMapping(value = APPLE_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AppleAppSiteAssociation> appleAppSiteAssociation() {
        if (!StringUtils.hasText(properties.appleTeamId())) {
            return ResponseEntity.notFound().build();
        }
        var appId = properties.appleTeamId().strip() + "." + properties.iosBundleId();
        var detail = new AppLinksDetail(List.of(appId), List.of(Map.of(APPLE_PATH_KEY, INVITE_PATHS)));
        return ResponseEntity.ok(new AppleAppSiteAssociation(new AppLinks(List.of(detail))));
    }

    @GetMapping(value = ANDROID_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<AssetLink>> assetLinks() {
        if (properties.androidCertFingerprints().isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        var target = new AssetTarget(ANDROID_NAMESPACE, properties.androidPackage(),
                properties.androidCertFingerprints());
        return ResponseEntity.ok(List.of(new AssetLink(List.of(ANDROID_RELATION), target)));
    }
}
