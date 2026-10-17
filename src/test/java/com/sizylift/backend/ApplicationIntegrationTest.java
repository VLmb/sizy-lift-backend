package com.sizylift.backend;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import com.jayway.jsonpath.JsonPath;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApplicationIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse(
            "postgres:18@sha256:74935e72241653ca55e0414067e6d8763aceb8a810eb51b452253ec3dcfc4336")
            .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("sizylift_test")
            .withUsername("sizylift");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SpringLiquibase liquibase;

    @Value("${local.server.port}")
    int port;

    @Test
    void startsHttpServerWithHealthyDatabase() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health"))
                    .GET()
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(JsonPath.<String>read(response.body(), "$.status")).isEqualTo("UP");
        }
    }

    @Test
    void appliesLiquibaseMigrationToRealPostgres() {
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.schemata WHERE schema_name = 'sizylift'", Long.class))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM public.databasechangelog WHERE id = '001-create-application-schema'", Long.class))
                .isEqualTo(1L);
    }

    @Test
    void migrationRemainsAppliedWhenUserSchemaAlreadyExists() throws Exception {
        // A matching username/schema changes PostgreSQL's default search_path on reconnect.
        liquibase.afterPropertiesSet();

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM public.databasechangelog", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'sizylift' AND table_name = 'databasechangelog'", Long.class))
                .isZero();
    }
}
