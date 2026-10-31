package com.sizylift.backend.deployment;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Закреплённый стек с production-конфигурацией принимает OTLP и применяет политику хранения. */
class TelemetryRetentionIntegrationTest {
    private static final Duration TIMEOUT = Duration.ofMinutes(3);
    private static final String TRACE_ID = "aabbccddeeff00112233445566778899";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @TempDir Path temporary;

    @Test
    void productionStackRetainsOneDayAndStillAcceptsEverySignal() throws Exception {
        var model = composeModel();
        var services = model.get("services");
        for (var service : services.properties()) {
            assertThat(service.getValue().at("/logging/driver").asString()).isEqualTo("json-file");
            assertThat(service.getValue().at("/logging/options/max-size").asString()).isEqualTo("10m");
            assertThat(service.getValue().at("/logging/options/max-file").asString()).isEqualTo("3");
        }
        var config = services.get("otel-lgtm");
        assertThat(config.get("volumes").valueStream().anyMatch(volume ->
                volume.get("type").asString().equals("volume") && volume.get("target").asString().equals("/data"))).isTrue();
        try (var stack = new GenericContainer<>(DockerImageName.parse(config.get("image").asString()))) {
            config.get("environment").properties().forEach(e -> stack.withEnv(e.getKey(), e.getValue().asString()));
            for (var volume : config.get("volumes")) {
                if (volume.get("type").asString().equals("bind")) {
                    stack.withCopyFileToContainer(MountableFile.forHostPath(volume.get("source").asString()),
                            volume.get("target").asString());
                }
            }
            stack.withExposedPorts(4318, 9090, 3100, 3200, 4040)
                    .waitingFor(Wait.forSuccessfulCommand("bash /otel-lgtm/retention-healthcheck.sh").withStartupTimeout(TIMEOUT));
            stack.start();
            verifyRetention(stack);
            verifySignals(stack);
            verifyFailedComponent(stack);
        }
    }

    private void verifyRetention(GenericContainer<?> stack) throws Exception {
        assertThat(JSON.readTree(get(stack, 9090, "/api/v1/status/flags").body())
                .get("data").get("storage.tsdb.retention.time").asString()).isIn("1d", "24h");
        var loki = yaml(get(stack, 3100, "/config").body());
        assertThat(nested(loki, "compactor", "retention_enabled")).isEqualTo(true);
        assertThat(nested(loki, "compactor", "working_directory")).isEqualTo("/data/loki/compactor");
        assertThat(nested(loki, "compactor", "delete_request_store")).isEqualTo("filesystem");
        assertThat(nested(loki, "limits_config", "retention_period").toString()).isIn("1d", "24h", "24h0m0s");
        assertThat(nested(loki, "limits_config", "max_query_lookback").toString()).isIn("1d", "24h", "24h0m0s");
        var tempoBody = get(stack, 3200, "/status/config").body();
        // Status API Tempo предваряет YAML строкой маршрута и разделителем.
        assertThat(tempoBody).startsWith("GET /status/config\n---\n");
        var tempo = yaml(tempoBody.substring(tempoBody.indexOf("---\n") + 4));
        assertThat(nested(tempo, "backend_scheduler", "provider", "compaction", "compaction", "block_retention"))
                .isEqualTo("24h0m0s");
        assertThat(nested(tempo, "backend_worker", "compaction", "block_retention")).isEqualTo("24h0m0s");
        var profilesResponse = get(stack, 4040, "/api/v1/status/config");
        assertThat(profilesResponse.statusCode()).isEqualTo(200);
        var profiles = yaml(profilesResponse.body());
        assertThat(nested(profiles, "limits", "retention_period").toString()).isIn("1d", "24h", "24h0m0s");
    }

    private void verifySignals(GenericContainer<?> stack) throws Exception {
        var now = Instant.now();
        long nanos = now.getEpochSecond() * 1_000_000_000L;
        post(stack, 4318, "/v1/metrics", """
                {"resourceMetrics":[{"scopeMetrics":[{"metrics":[{"name":"sizy_retention_probe",
                "gauge":{"dataPoints":[{"timeUnixNano":"%d","asDouble":42}]}}]}]}]}
                """.formatted(nanos), 200);
        post(stack, 4318, "/v1/logs", """
                {"resourceLogs":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"retention-probe"}}]},
                "scopeLogs":[{"logRecords":[{"timeUnixNano":"%d","body":{"stringValue":"retention probe"}}]}]}]}
                """.formatted(nanos), 200);
        post(stack, 4318, "/v1/traces", """
                {"resourceSpans":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"retention-probe"}}]},
                "scopeSpans":[{"spans":[{"traceId":"%s","spanId":"1122334455667788","name":"retention-probe",
                "kind":2,"startTimeUnixNano":"%d","endTimeUnixNano":"%d"}]}]}]}
                """.formatted(TRACE_ID, nanos, nanos + 1_000_000), 200);
        await().atMost(TIMEOUT).pollInterval(Duration.ofSeconds(2)).untilAsserted(() -> {
            assertThat(JSON.readTree(get(stack, 9090, "/api/v1/query?query=sizy_retention_probe").body())
                    .at("/data/result/0/value/1").asString()).isEqualTo("42");
            assertThat(get(stack, 3100, "/loki/api/v1/query_range?query=%7Bservice_name%3D%22retention-probe%22%7D").body())
                    .contains("retention probe");
            assertThat(get(stack, 3200, "/api/traces/" + TRACE_ID).statusCode()).isEqualTo(200);
        });
        assertThat(post(stack, 3100, "/loki/api/v1/push", """
                {"streams":[{"stream":{"service_name":"retention-probe"},"values":[["%d","expired probe"]]}]}
                """.formatted(nanos - Duration.ofHours(25).toNanos()), 400)).contains("too old");
    }

    private void verifyFailedComponent(GenericContainer<?> stack) throws Exception {
        assertThat(stack.execInContainer("bash", "/otel-lgtm/retention-healthcheck.sh").getExitCode()).isZero();
        // Закрытый порт должен давать unhealthy, а не пропускаться проверкой образа.
        var stopped = stack.execInContainer("bash", "-c", """
                for comm in /proc/[0-9]*/comm; do
                  if read -r name < "$comm" && [[ "$name" == loki ]]; then
                    pid=${comm#/proc/}
                    kill -KILL "${pid%/comm}"
                    exit
                  fi
                done
                exit 1
                """);
        assertThat(stopped.getExitCode()).as(stopped.getStderr()).isZero();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(stack.execInContainer("bash", "/otel-lgtm/retention-healthcheck.sh").getExitCode()).isNotZero());
    }

    private JsonNode composeModel() throws Exception {
        var values = new HashMap<String, String>();
        for (var line : Files.readAllLines(Path.of("deploy/.env.example"))) {
            if (line.isBlank() || line.startsWith("#")) continue;
            var parts = line.split("=", 2);
            values.put(parts[0], parts[1].isBlank() ? "local-retention-test-only" : parts[1]);
        }
        var env = temporary.resolve("retention.env");
        Files.write(env, values.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList());
        var output = temporary.resolve("compose.json");
        var errors = temporary.resolve("compose-errors.txt");
        var builder = new ProcessBuilder("docker", "compose", "--env-file", env.toString(),
                "-f", "deploy/compose.prod.yaml", "config", "--format", "json");
        values.keySet().forEach(builder.environment()::remove);
        var process = builder.redirectOutput(output.toFile()).redirectError(errors.toFile()).start();
        assertThat(process.waitFor()).as(Files.readString(errors)).isZero();
        return JSON.readTree(Files.readString(output));
    }

    private HttpResponse<String> get(GenericContainer<?> stack, int port, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(stack, port, path)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String post(GenericContainer<?> stack, int port, String path, String body, int status) throws Exception {
        var response = http.send(HttpRequest.newBuilder(uri(stack, port, path)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        return response.body();
    }

    private static URI uri(GenericContainer<?> stack, int port, String path) {
        return URI.create("http://" + stack.getHost() + ":" + stack.getMappedPort(port) + path);
    }

    private static Map<String, Object> yaml(String body) {
        return new Yaml().load(body);
    }

    private static Object nested(Map<String, Object> config, String... keys) {
        Object value = config;
        for (var key : keys) value = ((Map<?, ?>) value).get(key);
        return value;
    }
}
