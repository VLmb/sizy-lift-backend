package com.sizylift.backend.admin.service;

import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminRuntimeService {
    private static final List<String> PERIODS = List.of("1h", "24h");
    private final RestClient http = AdminHttp.client();
    private final String url;

    public AdminRuntimeService(@Value("${sizylift.admin.runtime-url:}") String url) {
        this.url = url;
    }

    public Containers containers() {
        if (url.isBlank()) return new Containers(Instant.now(), "unavailable", List.of());
        try {
            return http.get().uri(url + "/containers").retrieve().body(Containers.class);
        } catch (RuntimeException exception) {
            return new Containers(Instant.now(), "unavailable", List.of());
        }
    }

    public Logs logs(String id, String period, int limit) {
        if (!id.matches("[a-f0-9]{64}") || !PERIODS.contains(period) || limit < 1 || limit > 500)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        if (url.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        try {
            return http.get().uri(url + "/containers/{id}/logs?period={period}&limit={limit}", id, period, limit)
                    .retrieve().onStatus(status -> status.value() == 404, (request, response) -> {
                        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
                    }).body(Logs.class);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    public record Containers(Instant fetchedAt, String state, List<Container> items) {
    }

    public record Container(String id, String service, String name, String state, String health, int restarts) {
    }

    public record Logs(Instant fetchedAt, String containerId, List<String> lines, boolean truncated) {
    }
}
