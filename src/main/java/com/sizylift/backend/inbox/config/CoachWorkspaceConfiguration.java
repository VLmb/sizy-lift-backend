package com.sizylift.backend.inbox.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CoachWorkspaceProperties.class)
class CoachWorkspaceConfiguration {
}
