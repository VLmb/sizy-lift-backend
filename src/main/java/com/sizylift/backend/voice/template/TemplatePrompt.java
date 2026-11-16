package com.sizylift.backend.voice.template;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

import com.sizylift.backend.template.TemplateVocabulary;
import com.sizylift.backend.voice.service.VoiceCatalog;
import org.springframework.core.io.ClassPathResource;

/**
 * Инструкция модели шаблона (docs/voice.md, «Разбор шаблоном»): текст — {@code voice/template-prompt.v1.txt},
 * списки типов, ключей и видов подходов подставляются из словаря {@link TemplateVocabulary}, в конце —
 * каталог. Правила и системный каталог одинаковы у всех запросов и идут первыми — эту часть модель берёт из
 * кэша; свои упражнения и синонимы пользователя — в конце.
 */
public final class TemplatePrompt {
    /** Версия промпта — в логе отладки, чтобы понимать, каким текстом разобрана диктовка. */
    public static final String VERSION = "v1";
    static final String RESOURCE = "voice/template-prompt." + VERSION + ".txt";
    static final String RULES = rules();

    private TemplatePrompt() { }

    public static String build(VoiceCatalog.Prompt catalog) {
        var out = new StringBuilder(RULES);
        for (var e : catalog.system()) out.append(line(e)).append('\n');
        if (!catalog.own().isEmpty()) {
            out.append("\nСВОИ УПРАЖНЕНИЯ ПОЛЬЗОВАТЕЛЯ (тоже каталог)\n");
            for (var e : catalog.own()) out.append(line(e)).append('\n');
        }
        if (!catalog.aliases().isEmpty()) {
            out.append("\nКАК ПОЛЬЗОВАТЕЛЬ НАЗЫВАЕТ УПРАЖНЕНИЯ (сказанное → название)\n");
            for (var a : catalog.aliases()) out.append(a.spoken()).append(" → ").append(a.name()).append('\n');
        }
        return out.toString();
    }

    /** Текст промпта со списками из словаря, без каталога. */
    static String rules() {
        String text;
        try (var in = new ClassPathResource(RESOURCE).getInputStream()) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Нет промпта " + RESOURCE, e);
        }
        var types = TemplateVocabulary.exerciseTypes().stream()
                .map(t -> "  " + t.word() + " — " + t.description()).collect(Collectors.joining("\n"));
        var keys = TemplateVocabulary.exerciseTypes().stream()
                .map(t -> "  " + t.word() + ": " + (t.keys().isEmpty() ? "только заметка" : String.join(", ", t.keys())))
                .collect(Collectors.joining("\n"));
        var sets = TemplateVocabulary.setTypes().stream()
                .map(t -> "  " + (t.byDefault() ? t.word() + " (без ключа тип)" : "тип=" + t.word()) + " — "
                        + t.spoken().stream().map(s -> "«" + s + "»").collect(Collectors.joining(", "))
                        + (t.byDefault() ? " и все остальные" : ""))
                .collect(Collectors.joining("\n"));
        var common = TemplateVocabulary.commonKeys().stream().filter(k -> !"кол".equals(k)).collect(Collectors.joining(", "));
        return text.replace("{{EXERCISE_TYPES}}", types).replace("{{KEYS_BY_TYPE}}", keys)
                .replace("{{SET_TYPES}}", sets).replace("{{COMMON_KEYS}}", common);
    }

    private static String line(VoiceCatalog.PromptExercise e) {
        var type = TemplateVocabulary.word(e.resultType()).orElse(e.resultType());
        var variants = String.join("; ", e.variants());
        return type + " | " + e.name() + (variants.isEmpty() ? "" : " | " + variants);
    }
}
