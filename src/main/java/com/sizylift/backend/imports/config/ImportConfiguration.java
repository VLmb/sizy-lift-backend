package com.sizylift.backend.imports.config;

import java.net.http.HttpClient;
import java.time.Duration;

import com.sizylift.backend.imports.model.DeepSeekProgramParser;
import com.sizylift.backend.imports.model.ProgramParserModel;
import com.sizylift.backend.imports.service.ImportFailure;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ImportProperties.class)
public class ImportConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "sizylift.imports", name = "enabled", havingValue = "true")
    ProgramParserModel deepSeekProgramParser(ImportProperties properties, ObjectMapper json,
            @Value("${sizylift.imports.deepseek.api-key}") String key,
            @Value("${sizylift.imports.deepseek.url}") String url,
            @Value("${sizylift.imports.deepseek.model}") String model,
            @Value("${sizylift.imports.deepseek.timeout}") Duration timeout) {
        if (key.isBlank()) throw new IllegalArgumentException("Для AI-импорта нужен ключ DeepSeek");
        if (timeout.compareTo(properties.lease().minusSeconds(30)) >= 0)
            throw new IllegalArgumentException("Аренда задания должна быть длиннее таймаута модели");
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(timeout);
        return new DeepSeekProgramParser(RestClient.builder().requestFactory(factory).requestInterceptor((request, body, execution) ->
                new BoundedResponse(execution.execute(request, body))), json,
                key, url, model, properties.maxOutputTokens());
    }

    @Bean
    @ConditionalOnMissingBean(ProgramParserModel.class)
    ProgramParserModel disabledProgramParser() {
        return input -> { throw ImportFailure.unavailable(); };
    }

    private static class BoundedResponse implements org.springframework.http.client.ClientHttpResponse {
        private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
        private final org.springframework.http.client.ClientHttpResponse delegate;
        private java.io.InputStream body;
        BoundedResponse(org.springframework.http.client.ClientHttpResponse delegate) { this.delegate = delegate; }
        @Override public org.springframework.http.HttpStatusCode getStatusCode() throws java.io.IOException { return delegate.getStatusCode(); }
        @Override public String getStatusText() throws java.io.IOException { return delegate.getStatusText(); }
        @Override public org.springframework.http.HttpHeaders getHeaders() { return delegate.getHeaders(); }
        @Override public void close() { delegate.close(); }
        @Override public java.io.InputStream getBody() throws java.io.IOException {
            if (body == null) body = new java.io.FilterInputStream(delegate.getBody()) {
                private long consumed;
                private void account(int count) throws java.io.IOException {
                    if (count > 0) consumed += count;
                    if (consumed > MAX_RESPONSE_BYTES) throw new java.io.IOException("Ответ модели слишком большой");
                }
                @Override public int read() throws java.io.IOException {
                    account(0);
                    int next = in.read(); account(next < 0 ? 0 : 1); return next;
                }
                @Override public int read(byte[] bytes, int offset, int length) throws java.io.IOException {
                    account(0);
                    int count = in.read(bytes, offset, (int) Math.min(length, MAX_RESPONSE_BYTES - consumed + 1));
                    account(count); return count;
                }
            };
            return body;
        }
    }
}
