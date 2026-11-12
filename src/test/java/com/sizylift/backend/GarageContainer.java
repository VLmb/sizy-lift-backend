package com.sizylift.backend;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

/**
 * Хранилище видео для тестов — тот же образ и тот же файл настроек, что в compose (ADR-0012): один узел,
 * ключ и бакет создаются при запуске.
 */
public final class GarageContainer {

    public static final String BUCKET = "sizy-video";
    public static final String REGION = "garage";
    public static final String ACCESS_KEY = "GK0123456789abcdef01234567";
    public static final String SECRET_KEY = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String RPC_SECRET = "00000000000000000000000000000000000000000000000000000000000000aa";
    private static final String IMAGE =
            "dxflrs/garage:v2.5.0@sha256:677e5b758e4425a325b811c425c026bf35a4bf42f7ddbaa773de3f4fd779a5a5";
    private static final Path CONFIG = Path.of("deploy/garage/garage.toml");
    private static final int S3_PORT = 3900;
    /** Анонимный запрос к S3 API отклоняется — значит, сервер уже отвечает. */
    private static final int ANONYMOUS_STATUS = 403;

    private static GenericContainer<?> container;

    private GarageContainer() {
    }

    public static synchronized URI endpoint() {
        if (container == null) {
            container = start();
        }
        return URI.create("http://" + container.getHost() + ":" + container.getMappedPort(S3_PORT));
    }

    static void register(DynamicPropertyRegistry registry) {
        registry.add("sizylift.video.storage.endpoint", () -> endpoint().toString());
        registry.add("sizylift.video.storage.public-endpoint", () -> endpoint().toString());
        registry.add("sizylift.video.storage.region", () -> REGION);
        registry.add("sizylift.video.storage.bucket", () -> BUCKET);
        registry.add("sizylift.video.storage.access-key", () -> ACCESS_KEY);
        registry.add("sizylift.video.storage.secret-key", () -> SECRET_KEY);
    }

    private static GenericContainer<?> start() {
        try {
            var garage = new GenericContainer<>(DockerImageName.parse(IMAGE))
                    .withCopyToContainer(Transferable.of(Files.readString(CONFIG)), "/etc/garage.toml")
                    .withEnv("GARAGE_RPC_SECRET", RPC_SECRET)
                    .withEnv("GARAGE_DEFAULT_ACCESS_KEY", ACCESS_KEY)
                    .withEnv("GARAGE_DEFAULT_SECRET_KEY", SECRET_KEY)
                    .withEnv("GARAGE_DEFAULT_BUCKET", BUCKET)
                    .withCommand("/garage", "server", "--single-node", "--default-bucket")
                    .withExposedPorts(S3_PORT)
                    .waitingFor(Wait.forHttp("/").forPort(S3_PORT).forStatusCode(ANONYMOUS_STATUS)
                            .withStartupTimeout(Duration.ofMinutes(1)));
            garage.start();
            return garage;
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
