package com.sizylift.backend.web.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/** CORS для веба на отдельном адресе (локальная разработка); подключается в {@code SecurityConfiguration}. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WebProperties.class)
public class WebCorsConfiguration {

    private static final String API_PATHS = "/api/**";
    private static final List<String> METHODS = List.of("GET", "POST", "PUT", "DELETE");
    private static final List<String> HEADERS = List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE);
    private static final Duration PREFLIGHT_CACHE = Duration.ofHours(1);

    @Bean
    CorsConfigurationSource corsConfigurationSource(WebProperties properties) {
        var source = new UrlBasedCorsConfigurationSource();
        if (properties.allowedOrigins().isEmpty()) {
            return source;
        }
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(properties.allowedOrigins());
        cors.setAllowedMethods(METHODS);
        cors.setAllowedHeaders(HEADERS);
        // Токен идёт в заголовке, cookie не нужны.
        cors.setAllowCredentials(false);
        cors.setMaxAge(PREFLIGHT_CACHE);
        source.registerCorsConfiguration(API_PATHS, cors);
        return source;
    }
}
