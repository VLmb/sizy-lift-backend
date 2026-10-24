package com.sizylift.backend.applinks.controller;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import com.sizylift.backend.applinks.config.AppLinksProperties;
import com.sizylift.backend.coaching.service.CoachingService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

/**
 * Страница ссылки приглашения {@code https://<домен>/invite/<токен>}. С установленным приложением ссылку
 * перехватывает ОС и страница не открывается; без него — кнопка «Открыть в приложении», ссылки на магазины
 * и код для ручного ввода. Токен в адресе — секрет: страница не кэшируется, не индексируется и не передаёт
 * Referer.
 */
@RestController
public class InvitePageController {

    private static final String VALID_TEMPLATE = "applinks/invite-page.html";
    private static final String GONE_TEMPLATE = "applinks/invite-gone.html";
    private static final String TOKEN = "{{token}}";
    private static final String APP_LINK = "{{appLink}}";
    private static final String STORE_LINKS = "{{storeLinks}}";
    private static final String STORE_LINK = "<a class=\"store\" href=\"%s\">%s</a>";
    private static final String APP_STORE_LABEL = "App Store";
    private static final String PLAY_STORE_LABEL = "Google Play";
    private static final String CONTENT_SECURITY_POLICY = "default-src 'none'; style-src 'unsafe-inline'; "
            + "base-uri 'none'; form-action 'none'; frame-ancestors 'none'";
    private static final String CSP_HEADER = "Content-Security-Policy";
    private static final String REFERRER_POLICY = "Referrer-Policy";
    private static final String NO_REFERRER = "no-referrer";
    private static final String ROBOTS_TAG = "X-Robots-Tag";
    private static final String NO_INDEX = "noindex, nofollow";
    private static final MediaType HTML = new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8);

    private final CoachingService coaching;
    private final AppLinksProperties properties;
    private final String validTemplate = template(VALID_TEMPLATE);
    private final String goneTemplate = template(GONE_TEMPLATE);

    public InvitePageController(CoachingService coaching, AppLinksProperties properties) {
        this.coaching = coaching;
        this.properties = properties;
    }

    /** Действующее приглашение — 200; несуществующее, принятое, отозванное или истёкшее — 404 со страницей. */
    @GetMapping("/invite/{token}")
    public ResponseEntity<String> invitePage(@PathVariable String token) {
        if (!coaching.inviteUsable(token)) {
            return page(HttpStatus.NOT_FOUND, goneTemplate);
        }
        // Токен прошёл проверку формата (base64url), экранирование — вторая линия защиты.
        var escaped = HtmlUtils.htmlEscape(token);
        var body = validTemplate
                .replace(APP_LINK, HtmlUtils.htmlEscape(properties.appScheme() + "://invite/") + escaped)
                .replace(TOKEN, escaped)
                .replace(STORE_LINKS, storeLink(properties.appStoreUrl(), APP_STORE_LABEL)
                        + storeLink(properties.playStoreUrl(), PLAY_STORE_LABEL));
        return page(HttpStatus.OK, body);
    }

    private static ResponseEntity<String> page(HttpStatus status, String body) {
        return ResponseEntity.status(status)
                .contentType(HTML)
                .cacheControl(CacheControl.noStore())
                .header(CSP_HEADER, CONTENT_SECURITY_POLICY)
                .header(REFERRER_POLICY, NO_REFERRER)
                .header(ROBOTS_TAG, NO_INDEX)
                .body(body);
    }

    private static String storeLink(URI url, String label) {
        return url == null || url.toString().isBlank() ? ""
                : STORE_LINK.formatted(HtmlUtils.htmlEscape(url.toString()), label);
    }

    private static String template(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Invite page template is missing: " + path, e);
        }
    }
}
