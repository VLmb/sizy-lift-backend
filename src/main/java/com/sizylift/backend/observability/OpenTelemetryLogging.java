package com.sizylift.backend.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Connects the {@code OTEL} appender from logback-spring.xml to the OpenTelemetry SDK configured by Spring Boot,
 * so log records are exported over OTLP together with metrics and traces and carry the trace id.
 */
@Component
class OpenTelemetryLogging implements InitializingBean {

    private final OpenTelemetry openTelemetry;

    OpenTelemetryLogging(OpenTelemetry openTelemetry) {
        this.openTelemetry = openTelemetry;
    }

    @Override
    public void afterPropertiesSet() {
        OpenTelemetryAppender.install(openTelemetry);
    }
}
