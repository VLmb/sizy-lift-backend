package com.sizylift.backend.security;

import com.sizylift.backend.admin.service.AdminAccess;
import com.sizylift.backend.feedback.controller.FeedbackController;
import com.sizylift.backend.photo.controller.WorkoutPhotoController;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.http.HttpMethod;
import jakarta.servlet.DispatcherType;
import java.util.Set;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * Бэкенд — OAuth 2.0 resource server: токены выдаёт Keycloak, API их только проверяет.
 * Подпись, срок, issuer и audience проверяются по {@code spring.security.oauth2.resourceserver.jwt}.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    private static final String[] HEALTH_PATHS = {"/actuator/health", "/actuator/health/**"};
    /** Страница приглашения до входа (docs/coaching.md, «Приглашение до входа»). */
    private static final String PUBLIC_INVITE = "/api/v2/coaching/invites/*/public";

    /** Фото тренировок — только у дневника на устройстве: приложение и PWA; сайт их не показывает. */
    private static final Set<String> PHOTO_CLIENTS = Set.of("sizylift-mobile", "sizylift-pwa");
    private static final String AUTHORIZED_PARTY = "azp";

    /** Токен без {@code azp} — не клиент дневника; {@code Set.of} не принимает {@code null} в {@code contains}. */
    private static boolean photoClient(String authorizedParty) {
        return authorizedParty != null && PHOTO_CLIENTS.contains(authorizedParty);
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(requests -> requests
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, HEALTH_PATHS).permitAll()
                        .requestMatchers(HttpMethod.GET, PUBLIC_INVITE).permitAll()
                        .requestMatchers(HttpMethod.POST, FeedbackController.PATH).permitAll()
                        .requestMatchers("/actuator/**").denyAll()
                        .requestMatchers("/api/v2/admin", "/api/v2/admin/**").access((authentication, context) ->
                                new AuthorizationDecision(AdminAccess.allowed(authentication.get())))
                        .requestMatchers(WorkoutPhotoController.PATH, WorkoutPhotoController.PATH + "/**")
                        .access((authentication, context) -> new AuthorizationDecision(
                                authentication.get() instanceof JwtAuthenticationToken token
                                        && photoClient(token.getToken().getClaimAsString(AUTHORIZED_PARTY))))
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
