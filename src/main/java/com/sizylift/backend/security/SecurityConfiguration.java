package com.sizylift.backend.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Бэкенд — OAuth 2.0 resource server: токены выдаёт Keycloak, API их только проверяет.
 * Подпись, срок, issuer и audience проверяются по {@code spring.security.oauth2.resourceserver.jwt}.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    /** Здоровье, страница ссылки приглашения и файлы связи домена с приложением (docs/coaching.md). */
    private static final String[] PUBLIC_PATHS = {"/actuator/health", "/actuator/health/**", "/error",
        "/invite/*", "/.well-known/apple-app-site-association", "/.well-known/assetlinks.json"};

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer.jwt(Customizer.withDefaults()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Браузер не подставляет Bearer token сам, поэтому защита от CSRF не нужна.
                .csrf(AbstractHttpConfigurer::disable)
                .build();
    }
}
