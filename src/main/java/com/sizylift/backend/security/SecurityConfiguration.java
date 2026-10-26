package com.sizylift.backend.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.http.HttpMethod;
import jakarta.servlet.DispatcherType;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * Бэкенд — OAuth 2.0 resource server: токены выдаёт Keycloak, API их только проверяет.
 * Подпись, срок, issuer и audience проверяются по {@code spring.security.oauth2.resourceserver.jwt}.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    private static final String[] HEALTH_PATHS = {"/actuator/health", "/actuator/health/**"};

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(requests -> requests
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, HEALTH_PATHS).permitAll()
                        .requestMatchers("/actuator/**").denyAll()
                        .anyRequest().authenticated())
                // Источники — CorsConfigurationSource из web.config; пустой — CORS выключен.
                .cors(Customizer.withDefaults())
                .oauth2ResourceServer(resourceServer -> resourceServer.jwt(Customizer.withDefaults()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .addFilterAfter(new ApiBodyLimitFilter(), AuthorizationFilter.class)
                // Браузер не подставляет Bearer token сам, поэтому защита от CSRF не нужна.
                .csrf(AbstractHttpConfigurer::disable)
                .build();
    }
}
