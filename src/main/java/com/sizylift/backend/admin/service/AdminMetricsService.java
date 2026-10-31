package com.sizylift.backend.admin.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

@Service
public class AdminMetricsService {
    private static final Map<String, Integer> PERIODS = Map.of("15m", 900, "1h", 3600, "6h", 21600, "24h", 86400);
    private static final int POINTS = 120;
    private static final int DETAIL_POINTS = 600;
    private static final int MIN_STEP_SECONDS = 30;
    private static final int CACHE_SECONDS = 20;
    private static final String APP = "{service_name=\"sizy-lift-backend\"}";
    private static final String REQUESTS = "sum(rate(http_server_requests_seconds_count" + APP + "[5m]))"
            + " or sum(histogram_count(rate(http_server_requests_seconds" + APP + "[5m])))"
            + " or sum(rate(http_server_requests_milliseconds_count" + APP + "[5m]))"
            + " or sum(histogram_count(rate(http_server_requests_milliseconds" + APP + "[5m])))";
    private static final String ZERO_WHEN_APP_PRESENT = " or (0 * max(jvm_memory_used_bytes" + APP + "))";
    private static final Map<String, Definition> DEFINITIONS = definitions();
    private final RestClient http = AdminHttp.client();
    private final String url;
    private final Map<String, Metrics> cache = new LinkedHashMap<>();

    public AdminMetricsService(@Value("${sizylift.admin.prometheus-url:}") String url) {
        this.url = url;
    }

    public Metrics metrics(String period) {
        return metrics(period, false);
    }

    public synchronized Metrics metrics(String period, boolean detail) {
        var seconds = PERIODS.get(period);
        if (seconds == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var cacheKey = period + ":" + detail;
        var previous = cache.get(cacheKey);
        if (previous != null && previous.fetchedAt().plusSeconds(CACHE_SECONDS).isAfter(Instant.now())) return previous;
        var now = Instant.now();
        var series = new ArrayList<Series>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = new LinkedHashMap<String, Future<Series>>();
            DEFINITIONS.forEach((id, definition) -> pending.put(id,
                    executor.submit(() -> series(id, definition, now, seconds, detail ? DETAIL_POINTS : POINTS))));
            for (var future : pending.values()) {
                try {
                    series.add(future.get());
                } catch (Exception exception) {
                    if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
                }
            }
        }
        var result = new Metrics(now, period, series);
        cache.put(cacheKey, result);
        return result;
    }

    private Series series(String id, Definition definition, Instant now, int seconds, int pointsLimit) {
        if (url.isBlank()) return new Series(id, definition.unit(), List.of(), "unavailable");
        try {
            var response = http.get().uri(url + "/api/v1/query_range?query={query}&start={start}&end={end}&step={step}&timeout=3s",
                    definition.query(), now.minusSeconds(seconds).getEpochSecond(), now.getEpochSecond(),
                    Math.max(MIN_STEP_SECONDS, seconds / pointsLimit)).retrieve().body(JsonNode.class);
            var results = response == null ? null : response.path("data").path("result");
            if (response == null || !"success".equals(response.path("status").asString()) || !results.isArray())
                return new Series(id, definition.unit(), List.of(), "unavailable");
            var points = new ArrayList<Point>();
            if (results.size() > 0) {
                for (var value : results.get(0).path("values")) {
                    double number = value.get(1).asDouble(Double.NaN);
                    if (Double.isFinite(number)) points.add(new Point(value.get(0).asLong(), number));
                }
            }
            return new Series(id, definition.unit(), points, points.isEmpty() ? "no_data" : "ok");
        } catch (RuntimeException exception) {
            return new Series(id, definition.unit(), List.of(), "unavailable");
        }
    }

    private static Map<String, Definition> definitions() {
        var definitions = new LinkedHashMap<String, Definition>();
        var filesystem = "{job=\"sizy-host\",mountpoint=\"/\",fstype!~\"tmpfs|overlay|squashfs\"}";
        definitions.put("diskUsed", new Definition("%", "100 * (1 - sum(node_filesystem_avail_bytes"
                + filesystem + ") / sum(node_filesystem_size_bytes" + filesystem + "))"));
        definitions.put("diskFree", new Definition("bytes", "sum(node_filesystem_avail_bytes" + filesystem + ")"));
        definitions.put("ramUsed", new Definition("%", "100 * (1 - sum(node_memory_MemAvailable_bytes{job=\"sizy-host\"})"
                + " / sum(node_memory_MemTotal_bytes{job=\"sizy-host\"}))"));
        definitions.put("ramTotal", new Definition("bytes", "sum(node_memory_MemTotal_bytes{job=\"sizy-host\"})"));
        definitions.put("swapUsed", new Definition("bytes", "sum(node_memory_SwapTotal_bytes{job=\"sizy-host\"}"
                + " - node_memory_SwapFree_bytes{job=\"sizy-host\"})"));
        definitions.put("cpu", new Definition("%", "100 * (1 - avg(rate(node_cpu_seconds_total{job=\"sizy-host\",mode=\"idle\"}[5m])))"));
        definitions.put("requests", new Definition("req/s", REQUESTS));
        definitions.put("errors", new Definition("req/s", "sum(rate(http_server_requests_seconds_count{service_name=\"sizy-lift-backend\",status=~\"5..\"}[5m]))"
                + " or sum(histogram_count(rate(http_server_requests_seconds{service_name=\"sizy-lift-backend\",status=~\"5..\"}[5m])))"
                + " or sum(rate(http_server_requests_milliseconds_count{service_name=\"sizy-lift-backend\",status=~\"5..\"}[5m]))"
                + " or sum(histogram_count(rate(http_server_requests_milliseconds{service_name=\"sizy-lift-backend\",status=~\"5..\"}[5m])))"
                + ZERO_WHEN_APP_PRESENT));
        definitions.put("latency", new Definition("seconds", "histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket" + APP + "[5m])))"
                + " or histogram_quantile(0.95, sum(rate(http_server_requests_seconds" + APP + "[5m])))"
                + " or histogram_quantile(0.95, sum by (le) (rate(http_server_requests_milliseconds_bucket" + APP + "[5m]))) / 1000"
                + " or histogram_quantile(0.95, sum(rate(http_server_requests_milliseconds" + APP + "[5m]))) / 1000"));
        definitions.put("syncRejected", new Definition("items/s", "(sum(rate(sizylift_sync_push_items_total{status=\"rejected\"}[5m]))"
                + ZERO_WHEN_APP_PRESENT + ") + (sum(rate(sizylift_publication_items_total{status=\"rejected\"}[5m]))"
                + ZERO_WHEN_APP_PRESENT + ")"));
        definitions.put("hostSampleTime", new Definition("timestamp", "max(timestamp(node_memory_MemTotal_bytes{job=\"sizy-host\"}))"));
        definitions.put("appSampleTime", new Definition("timestamp", "max(timestamp(jvm_memory_used_bytes" + APP + "))"));
        return definitions;
    }

    public record Definition(String unit, String query) {
    }

    public record Metrics(Instant fetchedAt, String period, List<Series> series) {
    }

    public record Series(String id, String unit, List<Point> points, String state) {
    }

    public record Point(long time, double value) {
    }
}
