package com.sizylift.backend.imports.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Google Таблица по ссылке и защита от SSRF (ADR-0008, раздел 5). Сеть — заглушка. */
class SheetFetcherTest {

    static final String SHEET_ID = "1AbCdEfGhIjKlMnOpQrStUvWxYz0123456789";
    static final String EXPORT = "https://docs.google.com/spreadsheets/d/" + SHEET_ID + "/export?format=csv&gid=";

    MockRestServiceServer google;
    SheetFetcher fetcher;

    @BeforeEach
    void stub() {
        var builder = RestClient.builder();
        google = MockRestServiceServer.bindTo(builder).build();
        fetcher = new SheetFetcher(builder.build(), ImportTestProperties.defaults());
    }

    @Test
    void exportAddressIsBuiltFromTheSheetIdAndTab() {
        assertThat(SheetFetcher.exportUri("https://docs.google.com/spreadsheets/d/" + SHEET_ID + "/edit#gid=42"))
                .hasToString(EXPORT + "42");
        assertThat(SheetFetcher.exportUri("https://docs.google.com/spreadsheets/d/" + SHEET_ID
                + "/edit?usp=sharing&gid=7")).hasToString(EXPORT + "7");
        assertThat(SheetFetcher.exportUri("https://docs.google.com/spreadsheets/d/" + SHEET_ID))
                .hasToString(EXPORT + "0");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://docs.google.com/spreadsheets/d/1AbCdEfGhIjKlMnOp",
            "https://docs.google.com.evil.example/spreadsheets/d/1AbCdEfGhIjKlMnOp",
            "https://docs.google.com@169.254.169.254/spreadsheets/d/1AbCdEfGhIjKlMnOp",
            "https://evil.example/?u=https://docs.google.com/spreadsheets/d/1AbCdEfGhIjKlMnOp",
            "https://docs.google.com/document/d/1AbCdEfGhIjKlMnOp",
            "https://127.0.0.1/spreadsheets/d/1AbCdEfGhIjKlMnOp",
            "file:///etc/passwd"})
    void onlyGoogleSheetLinksAreAccepted(String url) {
        assertThatThrownBy(() -> SheetFetcher.exportUri(url)).isInstanceOf(ImportException.class)
                .extracting(e -> ((ImportException) e).code()).isEqualTo("invalid_sheet_url");
    }

    @Test
    void followsRedirectsOnlyToGoogleContentHosts() {
        var content = "https://doc-0c-sheets.googleusercontent.com/export/abc";
        google.expect(once(), requestTo(EXPORT + "0"))
                .andRespond(withStatus(HttpStatus.TEMPORARY_REDIRECT).header(HttpHeaders.LOCATION, content));
        google.expect(once(), requestTo(content))
                .andRespond(withSuccess("Присед,5x5", MediaType.parseMediaType("text/csv")));

        assertThat(fetcher.fetchCsv("https://docs.google.com/spreadsheets/d/" + SHEET_ID + "/edit"))
                .isEqualTo("Присед,5x5");
        google.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://accounts.google.com/ServiceLogin", "http://doc-0c.googleusercontent.com/x",
            "https://169.254.169.254/latest/meta-data", "https://evil.googleusercontent.com.example/x"})
    void redirectsElsewhereMeanTheSheetIsNotShared(String location) {
        google.expect(once(), requestTo(EXPORT + "0"))
                .andRespond(withStatus(HttpStatus.FOUND).header(HttpHeaders.LOCATION, location));

        assertThatThrownBy(() -> fetcher.fetchCsv("https://docs.google.com/spreadsheets/d/" + SHEET_ID))
                .isInstanceOf(ImportException.class).extracting(e -> ((ImportException) e).code())
                .isEqualTo("sheet_not_accessible");
        google.verify();
    }

    @Test
    void loginPageMeansTheSheetIsNotShared() {
        google.expect(once(), requestTo(EXPORT + "0"))
                .andRespond(withSuccess("<html>Sign in</html>", MediaType.TEXT_HTML));

        assertThatThrownBy(() -> fetcher.fetchCsv("https://docs.google.com/spreadsheets/d/" + SHEET_ID))
                .isInstanceOf(ImportException.class).extracting(e -> ((ImportException) e).code())
                .isEqualTo("sheet_not_accessible");
    }

    @Test
    void responseSizeIsLimited() {
        google.expect(once(), requestTo(EXPORT + "0")).andRespond(withSuccess(
                "x".repeat(ImportTestProperties.MAX_FILE_BYTES + 1), MediaType.parseMediaType("text/csv")));

        assertThatThrownBy(() -> fetcher.fetchCsv("https://docs.google.com/spreadsheets/d/" + SHEET_ID))
                .isInstanceOf(ImportException.class).extracting(e -> ((ImportException) e).code())
                .isEqualTo("file_too_large");
    }
}
