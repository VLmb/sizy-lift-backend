package com.sizylift.backend.web.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Публичный CSV Google Sheets: размер, время и адреса каждого перехода ограничены. */
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
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public String fetch(String url) {
        var match = SHEET.matcher(url);
        if (!match.matches()) throw new InvalidSheet();
        var gid = GID.matcher(url);
        var uri = URI.create("https://docs.google.com/spreadsheets/d/" + match.group(1)
                + "/export?format=csv" + (gid.find() ? "&gid=" + gid.group(1) : ""));
        try {
            for (int attempt = 0; attempt <= MAX_REDIRECTS; attempt++) {
                validateDestination(uri);
                var response = http.send(HttpRequest.newBuilder(uri).timeout(TIMEOUT).GET().build(),
                        info -> new LimitedBody());
                if (response.statusCode() >= REDIRECT_MIN && response.statusCode() <= REDIRECT_MAX) {
                    uri = uri.resolve(response.headers().firstValue("Location").orElseThrow(UnavailableSheet::new));
                    continue;
                }
                if (response.statusCode() != HttpStatus.OK.value()
                        || !response.headers().firstValue("Content-Type").orElse("").startsWith("text/csv")) {
                    throw new UnavailableSheet();
                }
                return new String(response.body(), java.nio.charset.StandardCharsets.UTF_8);
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
        private Flow.Subscription subscription;

        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription next) { subscription = next; next.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (bytes.size() + buffer.remaining() > MAX_BYTES) {
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
