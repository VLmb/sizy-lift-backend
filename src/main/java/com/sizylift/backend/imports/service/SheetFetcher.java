package com.sizylift.backend.imports.service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import com.sizylift.backend.imports.config.ImportProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Google Таблица по ссылке «доступ для всех, у кого есть ссылка» → CSV одного листа (ADR-0008, раздел 5).
 * Защита от SSRF: принимается только {@code https://docs.google.com/spreadsheets/d/{id}}, адрес экспорта сервер
 * собирает сам из {@code id} и {@code gid}; редиректы выполняются вручную и только на HTTPS-хосты Google
 * ({@code docs.google.com}, {@code *.googleusercontent.com}); ответ ограничен по размеру.
 */
public class SheetFetcher {

    static final String EXPORT = "https://docs.google.com/spreadsheets/d/%s/export?format=csv&gid=%s";
    static final String DEFAULT_GID = "0";
    private static final Pattern SHEET_URL = Pattern.compile(
            "^https://docs\\.google\\.com/spreadsheets/d/([A-Za-z0-9_-]{10,100})(?:[/?#][^\\s]*)?$");
    private static final Pattern GID = Pattern.compile("[?#&]gid=(\\d{1,20})");
    private static final String GOOGLE_HOST = "docs.google.com";
    private static final String CONTENT_HOST_SUFFIX = ".googleusercontent.com";
    private static final String HTTPS = "https";

    private final RestClient rest;
    private final ImportProperties properties;

    public SheetFetcher(RestClient rest, ImportProperties properties) {
        this.rest = rest;
        this.properties = properties;
    }

    /** Адрес CSV-экспорта для ссылки на таблицу; иначе — ошибка ввода. */
    static URI exportUri(String url) {
        var matcher = url == null ? null : SHEET_URL.matcher(url.strip());
        if (matcher == null || !matcher.matches()) {
            throw ImportException.invalidInput("invalid_sheet_url",
                    "Нужна ссылка вида https://docs.google.com/spreadsheets/d/…");
        }
        var gid = GID.matcher(url);
        return URI.create(EXPORT.formatted(matcher.group(1), gid.find() ? gid.group(1) : DEFAULT_GID));
    }

    String fetchCsv(String url) {
        var target = exportUri(url);
        for (int redirect = 0; redirect <= properties.sheets().maxRedirects(); redirect++) {
            var response = get(target);
            if (response.location() == null) {
                return response.body();
            }
            target = allowedRedirect(target.resolve(response.location()));
        }
        throw notAccessible();
    }

    private Fetched get(URI uri) {
        try {
            return rest.get().uri(uri).accept(MediaType.TEXT_PLAIN, MediaType.ALL).exchange((request, response) -> {
                if (response.getStatusCode().is3xxRedirection()) {
                    var location = response.getHeaders().getFirst(HttpHeaders.LOCATION);
                    if (location == null) {
                        throw notAccessible();
                    }
                    return new Fetched(null, URI.create(location));
                }
                if (!response.getStatusCode().isSameCodeAs(HttpStatus.OK) || isHtml(response.getHeaders())) {
                    throw notAccessible();
                }
                return new Fetched(read(response.getBody()), null);
            });
        } catch (RestClientException e) {
            throw new ImportException(HttpStatus.SERVICE_UNAVAILABLE, "sheet_unavailable",
                    "Google Таблицы сейчас недоступны, попробуйте позже или загрузите файл");
        }
    }

    private String read(InputStream body) throws IOException {
        var bytes = body.readNBytes(properties.maxFileBytes() + 1);
        if (bytes.length > properties.maxFileBytes()) {
            throw ImportException.invalidInput("file_too_large", "Таблица больше " + properties.maxFileBytes()
                    + " байт");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static URI allowedRedirect(URI uri) {
        var host = uri.getHost();
        var allowed = HTTPS.equals(uri.getScheme()) && uri.getUserInfo() == null && host != null
                && (GOOGLE_HOST.equals(host) || host.endsWith(CONTENT_HOST_SUFFIX));
        if (!allowed) {
            throw notAccessible();
        }
        return uri;
    }

    /** Закрытая таблица отдаёт страницу входа (HTML) или редирект на accounts.google.com. */
    private static boolean isHtml(HttpHeaders headers) {
        var type = headers.getContentType();
        return type != null && type.isCompatibleWith(MediaType.TEXT_HTML);
    }

    private static ImportException notAccessible() {
        return new ImportException(HttpStatus.UNPROCESSABLE_CONTENT, "sheet_not_accessible",
                "Таблица недоступна по ссылке: откройте доступ «всем, у кого есть ссылка» или загрузите файл");
    }

    private record Fetched(String body, URI location) {
    }
}
