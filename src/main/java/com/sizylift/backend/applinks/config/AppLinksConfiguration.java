package com.sizylift.backend.applinks.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AppLinksProperties.class)
class AppLinksConfiguration {
}
