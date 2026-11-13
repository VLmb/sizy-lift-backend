package com.sizylift.backend.voice.config;

import java.net.http.HttpClient;
import java.time.Duration;

import com.sizylift.backend.voice.model.DeepSeekGapResolver;
import com.sizylift.backend.voice.model.DeepgramSpeechRecognizer;
import com.sizylift.backend.voice.model.GapResolver;
import com.sizylift.backend.voice.model.SpeechRecognizer;
import com.sizylift.backend.voice.service.VoiceFailure;
import com.sizylift.backend.voice.service.VoicePipeline;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Провайдеры голосового ввода. Включённая функция без ключа Deepgram — ошибка старта. Запасная модель
 * без ключа просто не вызывается: непонятые куски остаются непонятыми.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(VoiceProperties.class)
public class VoiceConfiguration {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

    @Bean
    SpeechRecognizer speechRecognizer(VoiceProperties properties, ObjectMapper json) {
        if (!properties.enabled()) return (audio, type, keyterms) -> { throw VoiceFailure.disabled(); };
        var deepgram = properties.deepgram();
        if (deepgram.apiKey().isBlank()) throw new IllegalArgumentException("Для голосового ввода нужен ключ Deepgram");
        return new DeepgramSpeechRecognizer(client(deepgram.timeout()), json, deepgram.apiKey(), deepgram.url(),
                deepgram.model(), deepgram.language());
    }

    @Bean
    GapResolver gapResolver(VoiceProperties properties, ObjectMapper json) {
        var fallback = properties.fallback();
        if (!properties.enabled() || !fallback.enabled() || fallback.apiKey().isBlank()) return new DisabledResolver();
        return new DeepSeekGapResolver(client(fallback.timeout()), json, fallback.apiKey(), fallback.url(),
                fallback.model(), fallback.maxOutputTokens());
    }

    @Bean
    VoicePipeline voicePipeline(SpeechRecognizer recognizer, GapResolver resolver, ObjectMapper json) {
        return new VoicePipeline(recognizer, resolver, json);
    }

    /** Таймаут ответа — на весь вызов: ответ провайдеров приходит одним куском. Редиректы не нужны. */
    public static RestClient.Builder client(Duration timeout) {
        var http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(timeout);
        return RestClient.builder().requestFactory(factory);
    }

    private static final class DisabledResolver implements GapResolver {
        @Override public Result resolve(String input) { throw new Unavailable(); }
        @Override public boolean enabled() { return false; }
    }
}
