package com.sizylift.backend.imports.model;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.sizylift.backend.imports.entity.ImportSession.Clarification;
import com.sizylift.backend.imports.entity.ImportSession.Source;
import com.sizylift.backend.web.dto.WebDtos.CatalogExercise;

public interface ProgramParserModel {
    Result parse(Input input);

    /** Модель видит только короткий alias и название; UUID восстанавливает сервер. */
    record CatalogEntry(String alias, UUID id, String name) {
        /**
         * Общие упражнения идут первыми в стабильном порядке, свои — после них. Так префикс запроса
         * с каталогом совпадает между импортами и попадает в кэш провайдера, а alias детерминирован
         * для одного снимка каталога.
         */
        public static List<CatalogEntry> of(List<CatalogExercise> catalog) {
            var sorted = new ArrayList<>(catalog);
            sorted.sort(Comparator.comparing((CatalogExercise ex) -> ex.ownerId() != null).thenComparing(CatalogExercise::id));
            var result = new ArrayList<CatalogEntry>(sorted.size());
            for (int i = 0; i < sorted.size(); i++)
                result.add(new CatalogEntry("c" + (i + 1), sorted.get(i).id(), sorted.get(i).name()));
            return List.copyOf(result);
        }
    }

    /**
     * Черновик прошлого раунда не передаётся: при уточнении модель возвращает только вопрос,
     * а вопрос и ответ уже есть в history.
     */
    record Input(Source source, List<CatalogEntry> catalog, LocalDate startDate,
                 List<Clarification> history, String repairMessage) { }
    record Result(String json, int inputTokens, int outputTokens, int cachedTokens) { }
}
