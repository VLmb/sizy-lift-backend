package com.sizylift.backend.voice.model;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * DeepSeek (формат OpenAI chat completions): инструкция — сообщением system, фраза — user, ответ — текст.
 * Рассуждения выключены, {@code temperature 0}: шаблон должен быть одинаковым на одну и ту же фразу.
 */
public class DeepSeekTemplateNormalizer implements TemplateNormalizer {
    private static final String COMPLETIONS = "/chat/completions";

    private final RestClient http;
    private final ObjectMapper json;
    private final String model;
    private final int maxOutputTokens;

    public DeepSeekTemplateNormalizer(RestClient.Builder http, ObjectMapper json, String key, String url, String model,
                                      int maxOutputTokens) {
        this.http = http.baseUrl(url).defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + key).build();
        this.json = json;
        this.model = model;
        this.maxOutputTokens = maxOutputTokens;
    }

    @Override
    public Result normalize(String instructions, String phrase) {
        var body = Map.of("model", model, "stream", false, "temperature", 0, "max_tokens", maxOutputTokens,
                "thinking", Map.of("type", "disabled"),
                "messages", List.of(Map.of("role", "system", "content", instructions),
                        Map.of("role", "user", "content", phrase)));
        try {
            var response = json.readTree(http.post().uri(COMPLETIONS).contentType(MediaType.APPLICATION_JSON)
                    .body(json.writeValueAsString(body)).retrieve().body(String.class));
            var choice = response.path("choices").path(0);
            var text = choice.path("message").path("content").asString("");
            // Обрезанный по лимиту ответ теряет последние упражнения: лучше ошибка и повтор.
            if (!"stop".equals(choice.path("finish_reason").asString("")) || text.isBlank()) throw new Unavailable();
            var usage = response.path("usage");
            return new Result(text, usage.path("prompt_tokens").asInt(), usage.path("prompt_cache_hit_tokens").asInt(),
                    usage.path("completion_tokens").asInt());
        } catch (RestClientException | JacksonException e) {
            throw new Unavailable();
        }
    }
}
