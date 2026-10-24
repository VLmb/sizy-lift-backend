package com.sizylift.backend.accountdeletion.config;

import java.net.http.HttpClient;

import com.sizylift.backend.accountdeletion.service.KeycloakAdminClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KeycloakAdminProperties.class)
class AccountDeletionConfiguration {

    @Bean
    KeycloakAdminClient keycloakAdminClient(KeycloakAdminProperties properties) {
        var http = HttpClient.newBuilder().connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var requestFactory = new JdkClientHttpRequestFactory(http);
        requestFactory.setReadTimeout(properties.readTimeout());
        var client = RestClient.builder()
                .baseUrl(properties.url().toString())
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
        return new KeycloakAdminClient(client, properties);
    }
}
