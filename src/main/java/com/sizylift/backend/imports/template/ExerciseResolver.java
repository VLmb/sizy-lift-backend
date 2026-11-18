package com.sizylift.backend.imports.template;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.sizylift.backend.imports.dto.ImportDraftV1.Resolution;
import com.sizylift.backend.imports.entity.ImportSession.SourceItem;
import com.sizylift.backend.template.TemplateVocabulary;
import com.sizylift.backend.voice.parser.RussianStemmer;
import com.sizylift.backend.voice.service.VoiceCatalog;

/**
 * Сопоставляет строку списка «Упражнения» с каталогом тем же словарём, что голосовой ввод: точное название каталога,
 * затем варианты, личные синонимы и опечатки ({@link VoiceCatalog#dictionary()}). Модель предлагает название, а
 * решение — matched, ambiguous или new — принимает сервер. Ссылки на источник находит сервер по названию.
 */
public final class ExerciseResolver {
    static final String MATCHED = "matched";
    static final String AMBIGUOUS = "ambiguous";
    static final String NEW = "new";
    private static final int MAX_CANDIDATES = 10;
    private static final int MAX_REFS = 5;
    private final VoiceCatalog catalog;
    private final List<SourceItem> source;

    List<SourceItem> source() { return source; }

    /** Решение по упражнению и тип результата, по которому разбираются его подходы. */
    public record Resolved(Resolution resolution, String resultType, String catalogName) { }

    public ExerciseResolver(VoiceCatalog catalog, List<SourceItem> source) {
        this.catalog = catalog;
        this.source = source;
    }

    public Resolved resolve(String key, ProgramLine.ExerciseEntry line) {
        var refs = refs(line.source());
        var main = find(line.name());
        var modelType = line.typeWord().isEmpty() ? "" : TemplateVocabulary.resultType(line.typeWord()).orElse("");
        if (main.isEmpty() && !line.marked()) main = find(line.source());
        if (main.isEmpty()) {
            var similar = similar(line.name());
            return new Resolved(new Resolution(key, line.source(), similar.isEmpty() || line.marked() ? NEW : AMBIGUOUS, "",
                    similar, capitalized(line.name()), modelType, refs), modelType, "");
        }
        var exercise = main.get();
        var alternatives = new LinkedHashSet<String>();
        for (var alternative : line.alternatives()) find(alternative).ifPresent(a -> alternatives.add(a.id().toString()));
        alternatives.remove(exercise.id().toString());
        // Модель назвала упражнение каталога, но пометила его новым: противоречие решает пользователь.
        if (alternatives.isEmpty() && exercise.exact() && !line.marked()) {
            return new Resolved(new Resolution(key, line.source(), MATCHED, exercise.id().toString(), List.of(), "", "", refs),
                    exercise.resultType(), exercise.name());
        }
        var candidates = new ArrayList<String>();
        candidates.add(exercise.id().toString());
        candidates.addAll(alternatives);
        // Неточное совпадение по варианту названия — пользователь подтверждает выбор.
        return new Resolved(new Resolution(key, line.source(), AMBIGUOUS, "", List.copyOf(candidates.subList(0,
                Math.min(candidates.size(), MAX_CANDIDATES))), "", "", refs), exercise.resultType(), exercise.name());
    }

    /** Решение из сохранённого черновика: тип для разбора подходов — из каталога или предложенный моделью. */
    public Resolved restore(Resolution resolution) {
        var id = switch (resolution.status()) {
            case MATCHED -> resolution.exerciseId();
            case AMBIGUOUS -> resolution.candidates().isEmpty() ? "" : resolution.candidates().getFirst();
            default -> "";
        };
        if (!id.isEmpty()) {
            var entry = catalog.byId(UUID.fromString(id));
            if (entry.isPresent()) return new Resolved(resolution, entry.get().resultType(), entry.get().name());
        }
        return new Resolved(resolution, resolution.proposedResultType(), "");
    }

    /** Упражнение «Упражнение:», которого нет в списке: модель нарушила шаблон, но подходы не теряются. */
    public Resolved unlisted(String key, String name) {
        return resolve(key, new ProgramLine.ExerciseEntry(name, name, "", false, List.of()));
    }

    private record Found(UUID id, String name, String resultType, boolean exact) { }

    private Optional<Found> find(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        var key = key(name);
        var exact = catalog.entries().stream().filter(e -> key(e.name()).equals(key)).findFirst();
        if (exact.isPresent()) return Optional.of(new Found(exact.get().id(), exact.get().name(), exact.get().resultType(), true));
        var found = catalog.dictionary().find(name);
        if (found == null) return Optional.empty();
        var e = found.exercise();
        return Optional.of(new Found(UUID.fromString(e.id()), e.name(), e.resultType(), found.exact()));
    }

    private List<String> similar(String name) {
        return catalog.dictionary().similar(name).stream().map(e -> e.id()).distinct().limit(MAX_CANDIDATES).toList();
    }

    /** Ячейки или строки источника, где написано название; ссылки — подсказка интерфейсу, не проверка. */
    private List<String> refs(String name) {
        var wanted = compact(name);
        if (wanted.isEmpty()) return List.of();
        return source.stream().filter(item -> compact(item.text()).contains(wanted)).map(SourceItem::ref)
                .limit(MAX_REFS).toList();
    }

    /**
     * Слова исходного названия, которых нет в названии каталога: «Жим лёжа (объём)» → «Жим штанги лёжа» даёт
     * «объём». Сервер переносит их в комментарий упражнения, чтобы уточнение источника не потерялось.
     */
    public static String qualifier(String source, String catalogName) {
        if (catalogName == null || catalogName.isBlank()) return "";
        var known = new LinkedHashSet<String>();
        for (var word : words(catalogName)) known.add(RussianStemmer.stem(word));
        var extra = new ArrayList<String>();
        for (var word : words(source)) if (!known.contains(RussianStemmer.stem(word))) extra.add(word);
        return String.join(" ", extra);
    }

    private static List<String> words(String text) {
        return List.of(text.toLowerCase(Locale.ROOT).replace('ё', 'е').split("[^\\p{L}\\p{N}]+")).stream()
                .filter(w -> !w.isEmpty()).toList();
    }

    private static String key(String name) {
        return name.strip().toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("\\s+", " ");
    }

    private static String compact(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static String capitalized(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }
}
