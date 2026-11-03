package com.sizylift.backend.imports;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import com.sizylift.backend.imports.entity.ImportSession.Source;
import com.sizylift.backend.imports.entity.ImportSession.SourceItem;
import com.sizylift.backend.imports.model.DeepSeekProgramParser;
import com.sizylift.backend.imports.model.ModelFailure;
import com.sizylift.backend.imports.model.ProgramParserModel;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class DeepSeekProgramParserTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final DeepSeekProgramParser parser = new DeepSeekProgramParser(builder, json, "test-secret",
            "https://deepseek.test", "deepseek-flash", 32768);

    @Test
    void sendsOnlyIdsAndNamesWithStrictToolsAndExplicitlyDisabledThinking() {
        server.expect(requestTo("https://deepseek.test/beta/chat/completions")).andExpect(request -> {
            var body = json.readTree(((MockClientHttpRequest) request).getBodyAsString());
            assertThat(body.get("model").asString()).isEqualTo("deepseek-flash");
            assertThat(body.at("/thinking/type").asString()).isEqualTo("disabled");
            assertThat(body.at("/tools/0/function/strict").asBoolean()).isTrue();
            assertThat(body.at("/tool_choice/function/name").asString()).isEqualTo("emit_program_import");
            var context = json.readTree(body.at("/messages/1/content").asString());
            assertThat(context.at("/catalog/0").size()).isEqualTo(2);
            assertThat(context.at("/catalog/0/id").asString()).isEqualTo(ImportFixtures.SQUAT.toString());
            assertThat(context.at("/catalog/0/name").asString()).isEqualTo("Присед");
        }).andRespond(withSuccess(response("tool_calls"), MediaType.APPLICATION_JSON));
        var result = parser.parse(input());
        assertThat(result.json()).isEqualTo("{}");
        assertThat(result.inputTokens()).isEqualTo(100);
        server.verify();
    }

    @Test
    void doesNotPerformHiddenRetriesAndDoesNotExposeTheProviderBody() {
        server.expect(requestTo("https://deepseek.test/beta/chat/completions"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "7").body("sensitive-provider-payload"));
        assertThatThrownBy(() -> parser.parse(input())).isInstanceOfSatisfying(ModelFailure.class, error -> {
            assertThat(error.retryable()).isTrue();
            assertThat(error.retryAfter()).isEqualTo(Duration.ofSeconds(7));
            assertThat(error.getMessage()).doesNotContain("sensitive", "test-secret");
            assertThat(error.getCause()).isNull();
        });
        server.verify();
    }

    @Test
    void rejectsATruncatedAnswerEvenIfItContainsValidJson() {
        server.expect(requestTo("https://deepseek.test/beta/chat/completions"))
                .andRespond(withSuccess(response("length"), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> parser.parse(input())).isInstanceOf(ModelFailure.class);
        server.verify();
    }

    private ProgramParserModel.Input input() {
        return new ProgramParserModel.Input(new Source(List.of(new SourceItem("text:1", ImportFixtures.TEXT))),
                List.of(new ProgramParserModel.CatalogEntry(ImportFixtures.SQUAT, "Присед")),
                LocalDate.of(2026, 11, 9), null, List.of(), "");
    }
    private String response(String finish) {
        return """
                {"id":"test","object":"chat.completion","created":0,"model":"deepseek-flash",
                 "choices":[{"index":0,"finish_reason":"%s","message":{"role":"assistant",
                  "tool_calls":[{"id":"call-1","type":"function","function":{"name":"emit_program_import","arguments":"{}"}}]}}],
                 "usage":{"prompt_tokens":100,"completion_tokens":10,"total_tokens":110}}
                """.formatted(finish);
    }
}
