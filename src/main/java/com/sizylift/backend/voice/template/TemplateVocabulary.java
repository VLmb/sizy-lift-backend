package com.sizylift.backend.voice.template;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Словарь шаблона ({@code voice/template-keys.v1.json}): типы упражнений словами шаблона и допустимые ключи
 * подходов, общие ключи, виды подходов. Один источник и для промпта, и для строгого разборщика: ключ, которого
 * нет в словаре, не появится в промпте и не будет принят разборщиком.
 */
public final class TemplateVocabulary {
    static final String RESOURCE = "voice/template-keys.v1.json";

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExerciseType(String resultType, String word, String description, List<String> keys) { }

    /** {@code value} — тип подхода приложения; {@code fallback} — подходы без ключа «тип» (в JSON — default). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SetType(String value, String word, List<String> spoken, @JsonProperty("default") Boolean fallback) {
        public boolean byDefault() { return Boolean.TRUE.equals(fallback); }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Data(List<ExerciseType> exerciseTypes, List<String> commonKeys, List<SetType> setTypes) { }

    private static final Data DATA = load();

    private TemplateVocabulary() { }

    public static List<ExerciseType> exerciseTypes() { return DATA.exerciseTypes(); }

    public static List<String> commonKeys() { return DATA.commonKeys(); }

    public static List<SetType> setTypes() { return DATA.setTypes(); }

    /** Все ключи подходов, в нижнем регистре: общие и ключи всех типов упражнений. */
    public static Set<String> allKeys() {
        return Stream.concat(commonKeys().stream(), exerciseTypes().stream().flatMap(t -> t.keys().stream()))
                .map(TemplateVocabulary::key).collect(Collectors.toUnmodifiableSet());
    }

    /** Ключи значений, допустимые для типа результата (без общих); неизвестный тип — все. */
    public static Set<String> keysFor(String resultType) {
        return exerciseTypes().stream().filter(t -> t.resultType().equals(resultType)).findFirst()
                .map(t -> t.keys().stream().map(TemplateVocabulary::key).collect(Collectors.toUnmodifiableSet()))
                .orElseGet(() -> exerciseTypes().stream().flatMap(t -> t.keys().stream()).map(TemplateVocabulary::key)
                        .collect(Collectors.toUnmodifiableSet()));
    }

    public static Optional<String> word(String resultType) {
        return exerciseTypes().stream().filter(t -> t.resultType().equals(resultType)).map(ExerciseType::word).findFirst();
    }

    public static Optional<String> resultType(String word) {
        var w = normalized(word);
        return exerciseTypes().stream().filter(t -> normalized(t.word()).equals(w)).map(ExerciseType::resultType).findFirst();
    }

    /** Вид подхода по слову шаблона («разминка», «топ-сет», «бекофф»); неизвестное — пусто. */
    public static Optional<String> setType(String word) {
        var w = normalized(word).replace("-", "").replace(" ", "");
        return setTypes().stream()
                .filter(t -> Stream.concat(Stream.of(t.word()), t.spoken().stream())
                        .anyMatch(s -> normalized(s).replace("-", "").replace(" ", "").equals(w)))
                .map(SetType::value).findFirst();
    }

    static String key(String key) { return key.toLowerCase(Locale.ROOT); }

    private static String normalized(String s) { return s.strip().toLowerCase(Locale.ROOT).replace('ё', 'е'); }

    private static Data load() {
        try (var in = new ClassPathResource(RESOURCE).getInputStream()) {
            return JsonMapper.builder().build().readValue(in, Data.class);
        } catch (IOException e) {
            throw new IllegalStateException("Нет словаря шаблона " + RESOURCE, e);
        }
    }
}
