package com.sizylift.backend.applinks.dto;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Файлы {@code /.well-known} в форматах Apple
 * (https://developer.apple.com/documentation/xcode/supporting-associated-domains) и Google
 * (https://developer.android.com/training/app-links/verify-android-applinks), сверено 23.10.2026.
 */
public final class AppLinksDtos {

    private AppLinksDtos() {
    }

    public record AppleAppSiteAssociation(AppLinks applinks) {
    }

    public record AppLinks(List<AppLinksDetail> details) {
    }

    /** {@code components} — шаблоны путей, которые открывает приложение: {@code {"/": "/invite/*"}}. */
    public record AppLinksDetail(List<String> appIDs, List<Map<String, String>> components) {
    }

    public record AssetLink(List<String> relation, AssetTarget target) {
    }

    public record AssetTarget(String namespace, @JsonProperty("package_name") String packageName,
                              @JsonProperty("sha256_cert_fingerprints") List<String> sha256CertFingerprints) {
    }
}
