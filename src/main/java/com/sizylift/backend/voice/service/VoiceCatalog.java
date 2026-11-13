package com.sizylift.backend.voice.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.sizylift.backend.voice.parser.ExerciseDictionary;

/**
 * Упражнения, которые можно назвать в диктовке: системные с синонимами, свои упражнения владельца и вызывающего
 * и личные синонимы вызывающего. Из него собираются словарь разбора, подсказки распознавания и каталог для модели.
 */
public record VoiceCatalog(ExerciseDictionary dictionary, List<Entry> entries, List<String> keyterms) {
    /** {@code alias} — короткий id для модели (c1, c2…): длинный UUID она может исказить. */
    public record Entry(String alias, UUID id, String name, String shortName, String resultType) { }

    /** Упражнение каталога; {@code own} — своё упражнение пользователя. */
    public record Source(UUID id, boolean own, String name, String shortName, String resultType) { }

    public record UserAlias(UUID exerciseId, String alias) { }

    private static final String ALIAS_PREFIX = "c";
    /** Слова параметров, которые распознавание чаще всего путает. */
    private static final List<String> PARAMETER_TERMS = List.of("подходов", "повторений", "килограмм", "до отказа", "RPE");

    public Optional<Entry> byAlias(String alias) { return entries.stream().filter(e -> e.alias().equals(alias)).findFirst(); }

    public Optional<Entry> byId(UUID id) { return entries.stream().filter(e -> e.id().equals(id)).findFirst(); }

    /**
     * Порядок в словаре задаёт приоритет при одинаковом варианте: личные синонимы, затем свои упражнения,
     * затем системные. Подсказки распознавания: сначала свои названия (их сервис не знает), затем личные
     * синонимы, затем короткие названия системных — сколько поместится в {@code keytermChars}.
     */
    public static VoiceCatalog build(List<Source> sources, Map<UUID, List<String>> systemAliases,
                                     List<UserAlias> userAliases, int keytermChars) {
        var entries = new ArrayList<Entry>();
        var exercises = new ArrayList<ExerciseDictionary.Exercise>();
        var own = sources.stream().filter(Source::own).toList();
        var system = sources.stream().filter(s -> !s.own()).toList();
        for (var a : userAliases) {
            sources.stream().filter(s -> s.id().equals(a.exerciseId())).findFirst().ifPresent(s -> exercises.add(
                    new ExerciseDictionary.Exercise(s.id().toString(), s.name(), s.resultType(), List.of(a.alias()))));
        }
        for (var s : own) exercises.add(exercise(s, List.of()));
        for (var s : system) exercises.add(exercise(s, systemAliases.getOrDefault(s.id(), List.of())));
        for (var s : system) entries.add(new Entry(ALIAS_PREFIX + (entries.size() + 1), s.id(), s.name(), s.shortName(), s.resultType()));
        for (var s : own) entries.add(new Entry(ALIAS_PREFIX + (entries.size() + 1), s.id(), s.name(), s.shortName(), s.resultType()));
        var terms = new ArrayList<String>();
        own.forEach(s -> terms.add(s.name()));
        userAliases.forEach(a -> terms.add(a.alias()));
        system.forEach(s -> terms.add(s.shortName() == null || s.shortName().isBlank() ? s.name() : s.shortName()));
        terms.addAll(PARAMETER_TERMS);
        return new VoiceCatalog(new ExerciseDictionary(exercises), List.copyOf(entries), keyterms(terms, keytermChars));
    }

    private static ExerciseDictionary.Exercise exercise(Source s, List<String> synonyms) {
        var aliases = new ArrayList<String>();
        aliases.add(s.name());
        if (s.shortName() != null && !s.shortName().isBlank()) aliases.add(s.shortName());
        aliases.addAll(synonyms);
        return new ExerciseDictionary.Exercise(s.id().toString(), s.name(), s.resultType(), aliases);
    }

    private static List<String> keyterms(List<String> terms, int budget) {
        var seen = new LinkedHashSet<String>();
        var out = new ArrayList<String>();
        int used = 0;
        for (var t : terms) {
            var term = t.strip();
            if (term.isEmpty() || !seen.add(term.toLowerCase(Locale.ROOT))) continue;
            if (used + term.length() > budget) break;
            used += term.length();
            out.add(term);
        }
        return out;
    }
}
