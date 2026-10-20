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

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApplicationIntegrationTest extends PostgresIntegrationTest {

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
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'sizylift' AND table_name = 'user_account'", Long.class))
                .isEqualTo(1L);
    }

    @Test
    void migrationRemainsAppliedWhenUserSchemaAlreadyExists() throws Exception {
        var appliedChangesets = jdbc.queryForObject("SELECT count(*) FROM public.databasechangelog", Long.class);

        // Совпадение имени пользователя и схемы меняет search_path PostgreSQL при переподключении.
        liquibase.afterPropertiesSet();

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM public.databasechangelog", Long.class)).isEqualTo(appliedChangesets);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'sizylift' AND table_name = 'databasechangelog'", Long.class))
                .isZero();
    }

    @Test
    void compositeCascadeForeignKeysHaveIndexesForTheirParents() {
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM public.databasechangelog WHERE id = '007-index-publication-parents'",
                Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForList("""
                SELECT c.conrelid::regclass::text
                FROM pg_constraint c
                WHERE c.connamespace = 'sizylift'::regnamespace
                  AND c.contype = 'f' AND c.confdeltype = 'c' AND cardinality(c.conkey) = 2
                  AND NOT EXISTS (
                    SELECT 1 FROM pg_index i
                    WHERE i.indrelid = c.conrelid AND i.indisvalid AND i.indisready
                      AND i.indpred IS NULL AND i.indnkeyatts >= 2
                      AND i.indkey[0] = c.conkey[1] AND i.indkey[1] = c.conkey[2]
                  )
                """, String.class)).isEmpty();
    }
}
