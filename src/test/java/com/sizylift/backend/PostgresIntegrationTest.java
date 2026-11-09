package com.sizylift.backend;

import java.util.Map;

import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Один контейнер PostgreSQL на все интеграционные тесты; останавливается вместе с JVM. */
@ActiveProfiles("test")
public abstract class PostgresIntegrationTest {
    private static final String TMPFS_ENV = "SIZYLIFT_TEST_POSTGRES_TMPFS";
    private static final String DATA_DIRECTORY = "/var/lib/postgresql";

    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse(
            "postgres:18@sha256:74935e72241653ca55e0414067e6d8763aceb8a810eb51b452253ec3dcfc4336")
            .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("sizylift_test")
            .withUsername("sizylift");

    static {
        // В среде с маленьким диском остаётся настоящий PostgreSQL, но временные данные — в tmpfs.
        if (Boolean.parseBoolean(System.getenv(TMPFS_ENV))) {
            POSTGRES.withTmpFs(Map.of(DATA_DIRECTORY, "rw"));
        }
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
