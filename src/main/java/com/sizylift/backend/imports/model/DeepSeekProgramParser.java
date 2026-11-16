package com.sizylift.backend.imports.model;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.sizylift.backend.imports.model.ProgramParserModel.Task;

/**
 * Один потоковый вызов DeepSeek (OpenAI-совместимый chat completions) без скрытых повторов: ответ — текст строк
 * шаблона, фрагменты сразу передаются слушателю, и сервер разбирает строки по мере генерации. {@code temperature 0}:
 * на один и тот же источник — одинаковый разбор. Рассуждения — только в первом вызове ({@code planEffort}); они
 * приходят отдельным полем reasoning_content, держат поток живым и в ответ не попадают. Ответ ограничен общим таймаутом и простоем
 * потока: зависшее соединение закрывается.
 */
public class DeepSeekProgramParser implements ProgramParserModel {
    private static final String COMPLETIONS = "/chat/completions";
    private static final String DATA = "data:";
    private static final String DONE = "[DONE]";
    private static final String LENGTH = "length";
    private static final Set<String> COMPLETED = Set.of("stop");
    private static final int MAX_TEXT_CHARS = 1_000_000;
    private static final int MAX_RETRY_AFTER_SECONDS = 120;
    private static final int TOO_MANY_REQUESTS = 429;
    private static final int SERVER_ERROR = 500;
    /** DeepSeek шлёт keep-alive и во время очереди; минута тишины — признак зависшего соединения. */
    private static final Duration IDLE = Duration.ofSeconds(60);
    private static final Duration WATCHDOG_TICK = Duration.ofSeconds(1);
    private final RestClient http;
    private final ObjectMapper json;
    private final String model;
    private final int maxOutputTokens;
    private final Duration timeout;
    /** Уровень рассуждений первого вызова (low, high, max); пусто — без рассуждений. */
    private final String planEffort;

    public DeepSeekProgramParser(RestClient.Builder http, ObjectMapper json, String key, String url,
                                 String model, int maxOutputTokens, Duration timeout) {
        this(http, json, key, url, model, maxOutputTokens, timeout, "");
    }

    public DeepSeekProgramParser(RestClient.Builder http, ObjectMapper json, String key, String url,
                                 String model, int maxOutputTokens, Duration timeout, String planEffort) {
        this.planEffort = planEffort == null ? "" : planEffort.strip();
        this.json = json;
        this.model = model;
        this.maxOutputTokens = maxOutputTokens;
        this.timeout = timeout;
        this.http = http.baseUrl(url).defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + key).build();
    }

    @Override
    public Result parse(Request request, Listener listener) {
        var body = new java.util.LinkedHashMap<String, Object>(Map.of("model", model, "stream", true,
                "stream_options", Map.of("include_usage", true), "temperature", 0, "max_tokens", maxOutputTokens,
                "messages", List.of(Map.of("role", "system", "content", request.system()),
                        Map.of("role", "user", "content", request.user()))));
        // Рассуждения — только на этапе понимания источника. Сопоставление с каталогом и недели исполняют
        // готовые правила и идут без рассуждений — быстрее и без разных толкований в разных частях.
        if (request.input().task().kind() == Task.Kind.UNDERSTAND && !planEffort.isEmpty()) {
            body.put("thinking", Map.of("type", "enabled"));
            body.put("reasoning_effort", planEffort);
        } else {
            body.put("thinking", Map.of("type", "disabled"));
        }
        try {
            return http.post().uri(COMPLETIONS).contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                    .body(json.writeValueAsString(body))
                    .exchange((req, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) throw failure(response);
                        return read(response, listener);
                    });
        } catch (ModelFailure e) {
            throw e;
        } catch (ResourceAccessException e) {
            throw new ModelFailure(true, Duration.ZERO);
        } catch (RuntimeException e) {
            throw invalid();
        }
    }

    /** Server-sent events: строки data с фрагментами, затем usage и [DONE]; комментарии keep-alive пропускаются. */
    private Result read(ClientHttpResponse response, Listener listener) throws IOException {
        var stream = new Stream(listener);
        var activity = new AtomicLong(System.nanoTime());
        var finished = new AtomicBoolean();
        var watchdog = Thread.ofVirtual().start(() -> watch(response, activity, finished));
        try (InputStream body = response.getBody();
             var reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                activity.set(System.nanoTime());
                if (!line.startsWith(DATA)) continue;
                var data = line.substring(DATA.length()).trim();
                if (data.equals(DONE)) break;
                try {
                    stream.accept(json.readTree(data));
                } catch (Stop e) {
                    // Остановлено сервером: соединение закрывается, полученный текст — как обрезанный ответ.
                    response.close();
                    return stream.stopped();
                }
            }
        } finally {
            finished.set(true);
            watchdog.interrupt();
        }
        return stream.result();
    }

    /** Закрывает ответ при простое потока или превышении общего таймаута: чтение получает IOException. */
    private void watch(ClientHttpResponse response, AtomicLong activity, AtomicBoolean finished) {
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (!finished.get()) {
                Thread.sleep(WATCHDOG_TICK);
                long now = System.nanoTime();
                if (now - activity.get() > IDLE.toNanos() || now > deadline) {
                    response.close();
                    return;
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class Stream {
        private final Listener listener;
        private final StringBuilder text = new StringBuilder();
        private final StringBuilder reasoning = new StringBuilder();
        private String finish;
        private JsonNode usage;

        Stream(Listener listener) { this.listener = listener; }

        void accept(JsonNode chunk) {
            if (chunk.hasNonNull("usage")) usage = chunk.get("usage");
            var choices = chunk.path("choices");
            for (int i = 0; i < choices.size(); i++) {
                var choice = choices.get(i);
                if (choice.path("index").asInt() != 0) throw invalid();
                var thought = choice.path("delta").path("reasoning_content");
                if (thought.isString() && reasoning.length() < MAX_TEXT_CHARS) reasoning.append(thought.asString());
                var content = choice.path("delta").path("content");
                if (content.isString() && !content.asString().isEmpty()) {
                    var delta = content.asString();
                    text.append(delta);
                    if (text.length() > MAX_TEXT_CHARS) throw invalid();
                    listener.onText(delta);
                }
                if (choice.hasNonNull("finish_reason")) finish = choice.get("finish_reason").asString();
            }
        }

        Result stopped() { return new Result(text.toString(), 0, 0, 0, true, reasoning.toString()); }

        Result result() {
            int input = usage == null ? 0 : usage.path("prompt_tokens").asInt();
            int output = usage == null ? 0 : usage.path("completion_tokens").asInt();
            // DeepSeek сообщает кэш как prompt_cache_hit_tokens; OpenAI-совместимое поле — запасной вариант.
            int cached = usage == null ? 0 : usage.has("prompt_cache_hit_tokens") ? usage.path("prompt_cache_hit_tokens").asInt()
                    : usage.path("prompt_tokens_details").path("cached_tokens").asInt();
            boolean truncated = LENGTH.equals(finish);
            if (!truncated && (finish == null || !COMPLETED.contains(finish) || text.isEmpty())) throw invalid();
            return new Result(text.toString(), input, output, cached, truncated, reasoning.toString());
        }
    }

    /** Тело ошибки провайдера не читается и не попадает в исключение или логи. */
    private static ModelFailure failure(ClientHttpResponse response) throws IOException {
        int code = response.getStatusCode().value();
        long delay = 0;
        try { delay = Long.parseLong(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)); }
        catch (NumberFormatException | NullPointerException ignored) { }
        return new ModelFailure(code == TOO_MANY_REQUESTS || code >= SERVER_ERROR,
                Duration.ofSeconds(Math.clamp(delay, 0, MAX_RETRY_AFTER_SECONDS)));
    }

    private static ModelFailure invalid() { return new ModelFailure(true, Duration.ZERO); }
}
