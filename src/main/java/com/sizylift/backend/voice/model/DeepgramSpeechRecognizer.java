package com.sizylift.backend.voice.model;

import java.util.List;

import com.sizylift.backend.voice.service.VoiceFailure;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Deepgram pre-recorded API: один синхронный запрос со звуком в теле. Подсказки словаря — параметры keyterm
 * (у nova-3 они работают и для русского). Тело ошибки провайдера не читается и не логируется.
 */
public class DeepgramSpeechRecognizer implements SpeechRecognizer {
    private static final String LISTEN = "/v1/listen";
    private static final String TOKEN = "Token ";

    private final RestClient http;
    private final ObjectMapper json;
    private final String model;
    private final String language;

    public DeepgramSpeechRecognizer(RestClient.Builder http, ObjectMapper json, String key, String url, String model,
                                    String language) {
        this.http = http.baseUrl(url).defaultHeader(HttpHeaders.AUTHORIZATION, TOKEN + key).build();
        this.json = json;
        this.model = model;
        this.language = language;
    }

    @Override
    public String transcribe(byte[] audio, String contentType, List<String> keyterms) {
        try {
            var body = http.post()
                    .uri(b -> b.path(LISTEN).queryParam("model", model).queryParam("language", language)
                            .queryParam("smart_format", true).queryParam("keyterm", keyterms.toArray()).build())
                    .contentType(MediaType.parseMediaType(contentType))
                    .body(audio)
                    .retrieve()
                    .body(String.class);
            var alternative = json.readTree(body).path("results").path("channels").path(0).path("alternatives").path(0);
            if (alternative.isMissingNode()) throw VoiceFailure.recognition();
            return alternative.path("transcript").asString("").strip();
        } catch (RestClientException | JacksonException | IllegalArgumentException e) {
            throw VoiceFailure.recognition();
        }
    }
}
