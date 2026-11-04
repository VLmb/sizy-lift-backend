package com.sizylift.backend.imports.model;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.sizylift.backend.imports.dto.ImportDraftV1;
import com.sizylift.backend.imports.dto.ImportPlan;
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
                 List<Clarification> history, Task task, String repairMessage) { }

    /**
     * Что сделать этим вызовом. Всё, кроме задачи и ошибок повтора, одинаково для всех вызовов сессии,
     * поэтому общий префикс запроса попадает в кэш провайдера.
     */
    record Task(Kind kind, int singleCallWorkouts, List<Integer> weeks, List<ImportPlan.Week> plan,
                List<ImportDraftV1.Resolution> exercises) {
        public enum Kind { PLAN, PLAN_ONLY, WEEKS }

        /** План; программу до singleCallWorkouts тренировок модель разбирает целиком в том же вызове. */
        public static Task plan(int singleCallWorkouts) { return new Task(Kind.PLAN, singleCallWorkouts, List.of(), List.of(), List.of()); }
        /** Только план: целиком программа не поместилась в один ответ. */
        public static Task planOnly() { return new Task(Kind.PLAN_ONLY, 0, List.of(), List.of(), List.of()); }
        /** Подробный разбор недель weeks большой программы по её плану. */
        public static Task weeks(ImportPlan plan, List<Integer> weeks) {
            var selected = plan.weeks().stream().filter(week -> weeks.contains(week.week())).toList();
            return new Task(Kind.WEEKS, 0, List.copyOf(weeks), selected, plan.exercises());
        }
    }
    record Result(String json, int inputTokens, int outputTokens, int cachedTokens) { }
}
