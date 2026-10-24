package com.sizylift.backend.notifications.config;

import java.net.http.HttpClient;

import com.sizylift.backend.notifications.service.ExpoPushClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NotificationProperties.class)
class NotificationConfiguration {

    @Bean
    ExpoPushClient expoPushClient(NotificationProperties properties) {
        var expo = properties.expo();
        var http = HttpClient.newBuilder().connectTimeout(expo.connectTimeout()).build();
        var requestFactory = new JdkClientHttpRequestFactory(http);
        requestFactory.setReadTimeout(expo.readTimeout());
        var client = RestClient.builder()
                .baseUrl(expo.url().toString())
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        if (StringUtils.hasText(expo.accessToken())) {
            client.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + expo.accessToken());
        }
        return new ExpoPushClient(client.build());
    }
}
