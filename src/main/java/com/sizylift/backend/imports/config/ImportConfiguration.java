package com.sizylift.backend.imports.config;

import java.net.http.HttpClient;
import java.time.Duration;

import com.sizylift.backend.imports.model.DeepSeekParserModel;
import com.sizylift.backend.imports.model.ProgramParserModel;
import com.sizylift.backend.imports.service.SheetFetcher;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ImportProperties.class)
class ImportConfiguration {

    @Bean
    ProgramParserModel programParserModel(ImportProperties properties) {
        var model = properties.model();
        var client = RestClient.builder()
                .baseUrl(model.url().toString())
                .requestFactory(requestFactory(model.connectTimeout(), model.readTimeout(),
                        HttpClient.Redirect.NEVER))
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + model.apiKey())
                .build();
        return new DeepSeekParserModel(client, model);
    }

    /** Редиректы не выполняются клиентом: {@link SheetFetcher} проверяет каждый адрес сам. */
    @Bean
    SheetFetcher sheetFetcher(ImportProperties properties) {
        var sheets = properties.sheets();
        var client = RestClient.builder()
                .requestFactory(requestFactory(sheets.connectTimeout(), sheets.readTimeout(),
                        HttpClient.Redirect.NEVER))
                .build();
        return new SheetFetcher(client, properties);
    }

    private static JdkClientHttpRequestFactory requestFactory(Duration connect, Duration read,
                                                              HttpClient.Redirect redirect) {
        var http = HttpClient.newBuilder().connectTimeout(connect).followRedirects(redirect).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(read);
        return factory;
    }
}
