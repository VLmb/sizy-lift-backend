package com.sizylift.backend.applinks.config;

import java.net.URI;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Ссылки приглашений открывают приложение (docs/coaching.md, «Ссылка приглашения»). В production
 * {@code appleTeamId} — {@code APPLE_TEAM_ID}, {@code androidCertFingerprints} — {@code ANDROID_CERT_FINGERPRINTS}.
 * Пустые значения выключают соответствующий файл {@code /.well-known}: он отвечает 404.
 *
 * @param appScheme               схема приложения, {@code <схема>://invite/<токен>}
 * @param appleTeamId             Team ID аккаунта Apple Developer
 * @param iosBundleId             bundle id приложения iOS
 * @param androidPackage          package приложения Android
 * @param androidCertFingerprints SHA-256 сертификатов подписи Android ({@code AA:BB:…}); при подписи
 *                                через Google Play — ключ из Play Console
 * @param appStoreUrl             страница в App Store; пустая — кнопки нет
 * @param playStoreUrl            страница в Google Play; пустая — кнопки нет
 */
@ConfigurationProperties("sizylift.app-links")
public record AppLinksProperties(String appScheme, String appleTeamId, String iosBundleId, String androidPackage,
                                 List<String> androidCertFingerprints, URI appStoreUrl, URI playStoreUrl) {

    public AppLinksProperties {
        androidCertFingerprints = androidCertFingerprints == null ? List.of()
                : androidCertFingerprints.stream().map(String::strip).filter(value -> !value.isEmpty()).toList();
    }
}
