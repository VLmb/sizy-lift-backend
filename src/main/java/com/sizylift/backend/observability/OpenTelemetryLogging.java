package com.sizylift.backend.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Подключает appender {@code OTEL} из logback-spring.xml к OpenTelemetry SDK, настроенному Spring Boot:
 * логи уходят по OTLP вместе с метриками и трейсами и несут trace id.
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
