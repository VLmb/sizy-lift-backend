package com.sizylift.backend.admin;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.sizylift.backend.admin.service.AdminMetricsService;
import com.sizylift.backend.admin.service.KeycloakDirectory;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminSourcesTest {
    @Test
    void readsAllKeycloakPagesAndCachesWithoutLeakingServiceCredentials() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var paths = new ArrayList<String>();
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().toString());
            var body = exchange.getRequestURI().getPath().endsWith("/token") ? "{\"access_token\":\"internal-only\"}"
                    : exchange.getRequestURI().getQuery().contains("first=0") ? people(100) : people(1);
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var directory = new KeycloakDirectory("http://127.0.0.1:" + server.getAddress().getPort(),
                    "https://auth.test/realms/sizylift", "reader", "server-only");
            assertThat(directory.users()).hasSize(101);
            assertThat(directory.users()).hasSize(101);
            assertThat(paths).hasSize(3);
            assertThat(paths.toString()).doesNotContain("server-only", "internal-only");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void missingTelemetryIsExplicitAndOnlyKnownPeriodsAreAllowed() {
        var metrics = new AdminMetricsService("");
        assertThat(metrics.metrics("1h").series()).allSatisfy(series -> {
            assertThat(series.state()).isEqualTo("unavailable");
            assertThat(series.points()).isEmpty();
        });
        assertThatThrownBy(() -> metrics.metrics("1y")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void detailHasSeparateCacheAndBoundedResolution() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var steps = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            for (var field : exchange.getRequestURI().getQuery().split("&")) {
                if (field.startsWith("step=")) steps.add(field.substring(5));
            }
            var bytes = "{\"status\":\"success\",\"data\":{\"result\":[]}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var metrics = new AdminMetricsService("http://127.0.0.1:" + server.getAddress().getPort());
            metrics.metrics("24h");
            var initialCalls = calls.get();
            metrics.metrics("24h");
            assertThat(calls.get()).isEqualTo(initialCalls);
            metrics.metrics("24h", true);
            assertThat(calls.get()).isGreaterThan(initialCalls);
            metrics.metrics("15m", true);
            assertThat(steps).containsExactlyInAnyOrder("720", "144", "30");
        } finally {
            server.stop(0);
        }
    }

    private static String people(int count) {
        var person = "{\"id\":\"identity\",\"firstName\":\"Test\",\"emailVerified\":false,\"enabled\":true,\"createdTimestamp\":1}";
        return "[" + String.join(",", Collections.nCopies(count, person)) + "]";
    }
}
