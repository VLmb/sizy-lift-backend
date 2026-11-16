package com.sizylift.backend.imports.model;

import java.time.LocalDate;
import java.util.List;

import com.sizylift.backend.imports.dto.ImportPlan;
import com.sizylift.backend.imports.entity.ImportSession.Clarification;
import com.sizylift.backend.imports.entity.ImportSession.Source;

/** Один вызов модели импорта: инструкция и сообщение, ответ — строки шаблона потоком. */
public interface ProgramParserModel {
    /** Один вызов модели; listener получает фрагменты текста ответа по мере генерации. */
    Result parse(Request request, Listener listener);

    /**
     * Слушатель бросает его, чтобы закончить ответ досрочно: дальше генерировать бессмысленно (например, большую
     * программу выгоднее дописать параллельными частями). Вызов возвращает уже полученный текст как обрезанный.
     */
    class Stop extends RuntimeException {
        public Stop() { super("Ответ модели остановлен сервером", null, false, false); }
    }

    @FunctionalInterface
    interface Listener {
        Listener NONE = delta -> { };
        void onText(String delta);
    }

    /** {@code system} — правила и каталог ({@link ImportPrompt}); сообщение пользователя — источник и задача. */
    record Request(String system, Input input) {
        public String user() { return ModelInputRenderer.render(input); }
    }

    /**
     * Что передаётся модели, кроме инструкции. Черновик прошлого раунда не передаётся: при уточнении модель
     * возвращает только вопрос, а вопрос и ответ уже есть в history.
     */
    record Input(Source source, LocalDate startDate, List<Clarification> history, Task task, String repairMessage) { }

    /**
     * Что сделать этим вызовом. Всё, кроме задачи и ошибок повтора, одинаково для всех вызовов сессии,
     * поэтому общий префикс запроса попадает в кэш провайдера.
     */
    record Task(Kind kind, int singleCallWorkouts, List<Integer> weeks, List<ImportPlan.Week> plan, List<String> exercises,
                List<String> rules) {
        public enum Kind { PLAN, PLAN_ONLY, WEEKS }

        /** Каркас; программу до singleCallWorkouts тренировок модель расписывает целиком в том же вызове. */
        public static Task plan(int singleCallWorkouts) { return new Task(Kind.PLAN, singleCallWorkouts, List.of(), List.of(), List.of(), List.of()); }
        /** Только каркас: ответ с тренировками не поместился в лимит вывода. */
        public static Task planOnly() { return new Task(Kind.PLAN_ONLY, 0, List.of(), List.of(), List.of(), List.of()); }
        /** Подробный разбор недель weeks по каркасу; exercises — названия упражнений программы как в источнике. */
        public static Task weeks(ImportPlan plan, List<Integer> weeks) {
            var selected = plan.weeks().stream().filter(week -> weeks.contains(week.week())).toList();
            return new Task(Kind.WEEKS, 0, List.copyOf(weeks), selected,
                    plan.exercises().stream().map(e -> e.originalName()).toList(), plan.rules());
        }
    }

    /** {@code truncated} — ответ обрезан по лимиту вывода: готовые строки можно использовать, остальное — нет. */
    record Result(String text, int inputTokens, int outputTokens, int cachedTokens, boolean truncated) { }
}
