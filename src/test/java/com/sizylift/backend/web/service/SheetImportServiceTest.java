package com.sizylift.backend.web.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SheetImportServiceTest {
    private static final String DOCUMENT = "12345678901234567890_abc";
    private static final String LINK = "https://docs.google.com/spreadsheets/d/" + DOCUMENT + "/edit";
    private final HttpClient http = mock(HttpClient.class);
    private final SheetImportService service = new SheetImportService(http);

    @Test
    void downloadsTheSelectedTabWithoutGoogleCredentialsAndPreservesUtf8() throws Exception {
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response(200, "text/csv; charset=utf-8", null, "Пн,Жим,75%\r\n"));
        assertThat(service.fetch(LINK + "#gid=42")).isEqualTo("Пн,Жим,75%\r\n");
        var request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(request.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(request.getValue().uri()).isEqualTo(URI.create(
                "https://docs.google.com/spreadsheets/d/" + DOCUMENT + "/export?format=csv&gid=42"));
        assertThat(request.getValue().headers().map()).isEmpty();
        assertThat(request.getValue().timeout()).isPresent();
    }

    @Test
    void noGidRequestsTheFirstTabAndChecksEveryGoogleExportRedirect() throws Exception {
        var destination = "https://doc-abc-123-sheets.googleusercontent.com/export/file";
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response(302, "text/html", destination, ""), response(200, "text/csv", null, "Жим,3×5"));
        assertThat(service.fetch(LINK)).isEqualTo("Жим,3×5");
        var request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(2)).send(request.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(request.getAllValues().getFirst().uri().getQuery()).isEqualTo("format=csv");
        assertThat(request.getAllValues().getLast().uri().toString()).isEqualTo(destination);
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 404})
    void aPrivateOrMissingDocumentIsUnavailableAndNoLoginIsAttempted(int code) throws Exception {
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response(code, "text/html", null, "private"));
        assertThatThrownBy(() -> service.fetch(LINK)).isInstanceOf(SheetImportService.UnavailableSheet.class);
        verify(http, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void anHtmlLoginPageIsNotAcceptedAsASpreadsheet() throws Exception {
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response(200, "text/html", null, "<html>Sign in</html>"));
        assertThatThrownBy(() -> service.fetch(LINK)).isInstanceOf(SheetImportService.UnavailableSheet.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://169.254.169.254/latest/meta-data", "https://docs.google.com.evil.example/export",
            "https://accounts.google.com/login", "https://user:password@docs.google.com/export", "https://docs.google.com:444/export"})
    void aRedirectCannotLeaveThePublicExportAllowlist(String location) throws Exception {
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response(302, "text/html", location, ""));
        assertThatThrownBy(() -> service.fetch(LINK)).isInstanceOf(SheetImportService.InvalidSheet.class);
        verify(http, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void redirectLoopsHaveABoundedNumberOfRequests() throws Exception {
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response(302, "text/html", LINK, ""));
        assertThatThrownBy(() -> service.fetch(LINK)).isInstanceOf(SheetImportService.UnavailableSheet.class);
        verify(http, times(4)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void anOversizedStreamIsCancelledBeforeItIsCollected() throws Exception {
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(call -> {
            HttpResponse.BodyHandler<byte[]> handler = call.getArgument(1);
            var subscriber = handler.apply(mock(HttpResponse.ResponseInfo.class));
            var subscription = mock(Flow.Subscription.class);
            subscriber.onSubscribe(subscription);
            subscriber.onNext(List.of(ByteBuffer.wrap(new byte[SheetImportService.MAX_BYTES])));
            subscriber.onNext(List.of(ByteBuffer.wrap(new byte[1])));
            verify(subscription).cancel();
            assertThat(subscriber.getBody().toCompletableFuture()).isCompletedExceptionally();
            throw new IOException("stream failed");
        });
        assertThatThrownBy(() -> service.fetch(LINK)).isInstanceOf(SheetImportService.UnavailableSheet.class);
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<byte[]> response(int status, String contentType, String location, String body) {
        var headers = new java.util.HashMap<String, List<String>>();
        headers.put("Content-Type", List.of(contentType));
        if (location != null) headers.put("Location", List.of(location));
        return new HttpResponse<>() {
            public int statusCode() { return status; }
            public HttpHeaders headers() { return HttpHeaders.of(headers, (key, value) -> true); }
            public byte[] body() { return body.getBytes(StandardCharsets.UTF_8); }
            public HttpRequest request() { return HttpRequest.newBuilder(URI.create(LINK)).build(); }
            public java.util.Optional<HttpResponse<byte[]>> previousResponse() { return java.util.Optional.empty(); }
            public java.util.Optional<javax.net.ssl.SSLSession> sslSession() { return java.util.Optional.empty(); }
            public URI uri() { return URI.create(LINK); }
            public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        };
    }
}
