package com.sizylift.backend.imports.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

import com.sizylift.backend.template.TemplateVocabulary;
import com.sizylift.backend.template.TemplateVocabulary.Scope;
import com.sizylift.backend.voice.service.VoiceCatalog;
import org.springframework.core.io.ClassPathResource;

/**
 * Инструкция модели импорта (docs/imports.md, «Шаблон»): текст — {@code imports/template-prompt.v1.txt}, списки
 * типов, ключей и видов подходов — из общего с голосом словаря {@link TemplateVocabulary}, в конце — каталог.
 * Правила и системный каталог одинаковы у всех импортов и идут первыми — их DeepSeek берёт из кэша; свои упражнения
 * и синонимы пользователя — в конце. Источник и задача идут отдельным сообщением.
 */
public final class ImportPrompt {
    /** Версия промпта — в логах и метриках, чтобы понимать, каким текстом разобран импорт. */
    public static final String VERSION = "v1";
    static final String RESOURCE = "imports/template-prompt." + VERSION + ".txt";
    static final String RULES = rules();

    private ImportPrompt() { }

    public static String build(VoiceCatalog.Prompt catalog) {
        var out = new StringBuilder(RULES);
        for (var e : catalog.system()) out.append(line(e)).append('\n');
        if (!catalog.own().isEmpty()) {
            out.append("\nСВОИ УПРАЖНЕНИЯ ПОЛЬЗОВАТЕЛЯ (тоже каталог)\n");
            for (var e : catalog.own()) out.append(line(e)).append('\n');
        }
        if (!catalog.aliases().isEmpty()) {
            out.append("\nКАК ПОЛЬЗОВАТЕЛЬ НАЗЫВАЕТ УПРАЖНЕНИЯ (как называет → название)\n");
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
                .map(t -> "  " + t.word() + ": " + (t.keys(Scope.IMPORT).isEmpty() ? "только заметка" : String.join(", ", t.keys(Scope.IMPORT))))
                .collect(Collectors.joining("\n"));
        var sets = TemplateVocabulary.setTypes(Scope.IMPORT).stream()
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
