package com.sizylift.backend.admin.service;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

final class AdminHttp {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private AdminHttp() {
    }

    static RestClient client() {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
        factory.setReadTimeout(TIMEOUT);
        return RestClient.builder().requestFactory(factory).build();
    }
}
