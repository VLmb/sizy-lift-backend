package com.sizylift.backend.imports.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

import com.sizylift.backend.template.TemplateVocabulary;
import com.sizylift.backend.template.TemplateVocabulary.Scope;
import com.sizylift.backend.voice.service.VoiceCatalog;
import org.springframework.core.io.ClassPathResource;

/**
 * Инструкции модели импорта по этапам (docs/imports.md, «Три этапа»): у каждого этапа свой короткий промпт только про
 * его работу. Понимание источника — {@code imports/understand-prompt}, сопоставление с каталогом —
 * {@code imports/match-prompt} (в конце — каталог), недели — {@code imports/weeks-prompt}. Списки типов, ключей и
 * видов подходов — из общего с голосом словаря {@link TemplateVocabulary}. Каждый промпт одинаков у всех импортов
 * (каталог — кроме своих упражнений пользователя в конце) и попадает в кэш DeepSeek.
 */
public final class ImportPrompt {
    /** Версия промптов — в логах и метриках, чтобы понимать, какими текстами разобран импорт. */
    public static final String VERSION = "v2";
    static final String UNDERSTAND = fill(read("imports/understand-prompt." + VERSION + ".txt"));
    static final String MATCH = fill(read("imports/match-prompt." + VERSION + ".txt"));
    static final String WEEKS = fill(read("imports/weeks-prompt." + VERSION + ".txt"));

    private ImportPrompt() { }

    public static String understand() { return UNDERSTAND; }

    public static String weeks() { return WEEKS; }

    public static String match(VoiceCatalog.Prompt catalog) {
        var out = new StringBuilder(MATCH);
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

    private static String read(String resource) {
        try (var in = new ClassPathResource(resource).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Нет промпта " + resource, e);
        }
    }

    /** Подставляет списки из словаря: типы упражнений, ключи по типам, общие ключи, виды подходов. */
    static String fill(String text) {
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
