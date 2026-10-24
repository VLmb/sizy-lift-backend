package com.sizylift.backend.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class TimeConfiguration {

    /** Единственный источник серверного времени; тесты подменяют его. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
