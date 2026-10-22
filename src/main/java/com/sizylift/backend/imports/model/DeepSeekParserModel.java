package com.sizylift.backend.imports.model;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sizylift.backend.imports.config.ImportProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * DeepSeek Chat Completions (https://api-docs.deepseek.com, сверено 22.10.2026): модель {@code deepseek-flash}
 * (DeepSeek-V4.1-Flash), JSON-режим {@code response_format: json_object}, режим рассуждений выключен — для
 * разбора он не нужен и дороже. 429, 500, 503 и сетевые ошибки — повторяемые; 400, 401, 402, 422 — нет.
 */
public class DeepSeekParserModel implements ProgramParserModel {

    static final String COMPLETIONS_PATH = "/chat/completions";
    static final String SYSTEM_PROMPT_RESOURCE = "imports/system-prompt.txt";
    static final String JSON_OBJECT = "json_object";
    static final String THINKING_DISABLED = "disabled";
    static final double TEMPERATURE = 0;
    private static final String ROLE_SYSTEM = "system";
    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";
    private static final String EXERCISES_HEADER = "\n\nИзвестные упражнения:\n";
    private static final String INPUT_HEADER = "Программа для разбора (верни json):\n";
    private static final String FINAL_ROUND = "Больше вопросов не задавай: прими разумные допущения, перечисли их "
            + "в assumptions и верни program в json.";

    private final RestClient rest;
    private final ImportProperties.Model properties;
    private final String systemPrompt;

    public DeepSeekParserModel(RestClient rest, ImportProperties.Model properties) {
        this.rest = rest;
        this.properties = properties;
        this.systemPrompt = loadSystemPrompt();
    }

    @Override
    public ModelReply parse(String input, List<String> exercises, List<Turn> dialogue, boolean finalRound) {
        var request = new CompletionRequest(properties.name(), messages(input, exercises, dialogue, finalRound),
                new ResponseFormat(JSON_OBJECT), new Thinking(THINKING_DISABLED), properties.maxTokens(),
                TEMPERATURE, false);
        var response = call(request);
        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            throw new ModelUnavailableException("Empty model response", true, null);
        }
        var content = response.choices().getFirst().message().content();
        var usage = response.usage() == null ? new Usage(0, 0) : response.usage();
        return new ModelReply(content == null ? "" : content, usage.promptTokens(), usage.completionTokens());
    }

    private List<Message> messages(String input, List<String> exercises, List<Turn> dialogue, boolean finalRound) {
        var messages = new ArrayList<Message>();
        messages.add(new Message(ROLE_SYSTEM, systemPrompt + EXERCISES_HEADER + String.join("\n", exercises)));
        messages.add(new Message(ROLE_USER, INPUT_HEADER + input));
        for (var turn : dialogue) {
            messages.add(new Message(turn.role() == Role.MODEL ? ROLE_ASSISTANT : ROLE_USER, turn.content()));
        }
        if (finalRound) {
            messages.add(new Message(ROLE_USER, FINAL_ROUND));
        }
        return messages;
    }

    private CompletionResponse call(CompletionRequest request) {
        try {
            return rest.post().uri(COMPLETIONS_PATH).contentType(MediaType.APPLICATION_JSON).body(request)
                    .retrieve().body(CompletionResponse.class);
        } catch (ResourceAccessException | HttpServerErrorException e) {
            throw new ModelUnavailableException("Model provider unavailable", true, e);
        } catch (HttpClientErrorException e) {
            var retryable = e.getStatusCode().isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS);
            throw new ModelUnavailableException("Model provider rejected the request with status "
                    + e.getStatusCode().value(), retryable, e);
        }
    }

    private static String loadSystemPrompt() {
        try (var input = new ClassPathResource(SYSTEM_PROMPT_RESOURCE).getInputStream()) {
            return StreamUtils.copyToString(input, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    record CompletionRequest(String model, List<Message> messages,
                             @JsonProperty("response_format") ResponseFormat responseFormat, Thinking thinking,
                             @JsonProperty("max_tokens") int maxTokens, double temperature, boolean stream) {
    }

    record Message(String role, String content) {
    }

    record ResponseFormat(String type) {
    }

    record Thinking(String type) {
    }

    record CompletionResponse(List<Choice> choices, Usage usage) {
    }

    record Choice(Message message,
                  @JsonProperty("finish_reason") String finishReason) {
    }

    record Usage(@JsonProperty("prompt_tokens") int promptTokens,
                 @JsonProperty("completion_tokens") int completionTokens) {
    }
}
