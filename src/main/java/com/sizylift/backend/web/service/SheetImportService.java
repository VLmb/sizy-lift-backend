package com.sizylift.backend.web.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Публичная Google Таблица — CSV одной вкладки или XLSX всей книги: размер, время и адреса каждого перехода ограничены. */
@Service
public class SheetImportService {
    static final int MAX_BYTES = 1_048_576;
    private static final int MAX_REDIRECTS = 3;
    private static final int REDIRECT_MIN = 300;
    private static final int REDIRECT_MAX = 399;
    private static final Pattern SHEET = Pattern.compile("https://docs\\.google\\.com/spreadsheets/d/([A-Za-z0-9_-]{20,100})(?:/[^?#]*)?(?:[?#].*)?");
    private static final Pattern EXPORT_HOST = Pattern.compile("doc-[a-z0-9-]+-sheets\\.googleusercontent\\.com");
    private static final Pattern GID = Pattern.compile("(?:[?#&])gid=(\\d{1,12})(?:&|$)");
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final String CSV = "text/csv";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String HTML = "text/html";
    private static final Pattern TAB_NAME = Pattern.compile("items\\.push\\(\\{name: \"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final int HEX = 16;
    private final HttpClient http;

    @Autowired
    public SheetImportService() {
        this(HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build());
    }

    SheetImportService(HttpClient http) { this.http = http; }

    /** Ссылка ведёт на Google Таблицу — проверка формы адреса без запроса в сеть. */
    public static boolean isSheetUrl(String url) {
        return url != null && SHEET.matcher(url.trim()).matches();
    }

    public String fetch(String url) {
        var match = SHEET.matcher(url);
        if (!match.matches()) throw new InvalidSheet();
        var gid = GID.matcher(url);
        var uri = URI.create("https://docs.google.com/spreadsheets/d/" + match.group(1)
                + "/export?format=csv" + (gid.find() ? "&gid=" + gid.group(1) : ""));
        return new String(download(uri, CSV, MAX_BYTES), StandardCharsets.UTF_8);
    }

    /**
     * Книга целиком: XLSX со всеми вкладками и их полные названия. Excel и выгрузка Google обрезают название листа
     * до 31 символа, поэтому полные названия берутся со страницы просмотра таблицы; если она недоступна — список пуст.
     */
    public Book fetchBook(String url, int maxBytes) {
        var match = SHEET.matcher(url);
        if (!match.matches()) throw new InvalidSheet();
        var base = "https://docs.google.com/spreadsheets/d/" + match.group(1);
        var xlsx = download(URI.create(base + "/export?format=xlsx"), XLSX, maxBytes);
        List<String> names;
        try {
            names = names(new String(download(URI.create(base + "/htmlview"), HTML, maxBytes), StandardCharsets.UTF_8));
        } catch (InvalidSheet | UnavailableSheet e) {
            names = List.of();
        }
        return new Book(xlsx, names);
    }

    /** XLSX книги и полные названия вкладок по порядку. */
    public record Book(byte[] xlsx, List<String> names) { }

    /** Названия вкладок со страницы просмотра: {@code items.push({name: "…", …})} в порядке вкладок. */
    static List<String> names(String html) {
        var result = new ArrayList<String>();
        var m = TAB_NAME.matcher(html);
        while (m.find()) result.add(unescape(m.group(1)));
        return List.copyOf(result);
    }

    /** Строка JavaScript: шестнадцатеричные и юникодные escape-последовательности и экранированные символы. */
    private static String unescape(String value) {
        var out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 >= value.length()) { out.append(c); continue; }
            char next = value.charAt(++i);
            if (next == 'x' && i + 2 < value.length()) {
                out.append((char) Integer.parseInt(value.substring(i + 1, i + 3), HEX)); i += 2;
            } else if (next == 'u' && i + 4 < value.length()) {
                out.append((char) Integer.parseInt(value.substring(i + 1, i + 5), HEX)); i += 4;
            } else out.append(next);
        }
        return out.toString();
    }

    private byte[] download(URI start, String contentType, int maxBytes) {
        var uri = start;
        try {
            for (int attempt = 0; attempt <= MAX_REDIRECTS; attempt++) {
                validateDestination(uri);
                var response = http.send(HttpRequest.newBuilder(uri).timeout(TIMEOUT).GET().build(),
                        info -> new LimitedBody(maxBytes));
                if (response.statusCode() >= REDIRECT_MIN && response.statusCode() <= REDIRECT_MAX) {
                    uri = uri.resolve(response.headers().firstValue("Location").orElseThrow(UnavailableSheet::new));
                    continue;
                }
                if (response.statusCode() != HttpStatus.OK.value()
                        || !response.headers().firstValue("Content-Type").orElse("").startsWith(contentType)) {
                    throw new UnavailableSheet();
                }
                return response.body();
            }
            throw new UnavailableSheet();
        } catch (IOException e) {
            throw new UnavailableSheet();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UnavailableSheet();
        }
    }

    static void validateDestination(URI uri) {
        var host = uri.getHost();
        if (!"https".equals(uri.getScheme()) || uri.getRawUserInfo() != null || uri.getPort() != -1
                || host == null || !(host.equals("docs.google.com") || EXPORT_HOST.matcher(host).matches())) {
            throw new InvalidSheet();
        }
    }

    /** Ограничение действует во время чтения, до выделения памяти под всё тело. */
    private static class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final int max;
        private Flow.Subscription subscription;

        LimitedBody(int max) { this.max = max; }

        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription next) { subscription = next; next.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (bytes.size() + buffer.remaining() > max) {
                    subscription.cancel(); result.completeExceptionally(new InvalidSheet()); return;
                }
                var chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { result.completeExceptionally(error); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public static class InvalidSheet extends RuntimeException { }
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public static class UnavailableSheet extends RuntimeException { }
}
