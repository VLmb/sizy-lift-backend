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
     * Что сделать этим вызовом (docs/imports.md, «Три этапа»). UNDERSTAND — понять источник: правила чтения,
     * упражнения как в источнике и план недель; MATCH — сопоставить названия с каталогом; WEEKS — расписать недели
     * по правилам. Источник и ответы одинаковы у вызовов сессии, задача — в конце: общий префикс попадает в кэш.
     */
    record Task(Kind kind, List<Integer> weeks, List<ImportPlan.Week> plan, List<String> exercises, List<String> rules) {
        public enum Kind { UNDERSTAND, MATCH, WEEKS }

        public static Task understand() { return new Task(Kind.UNDERSTAND, List.of(), List.of(), List.of(), List.of()); }
        /** {@code exercises} — строки «название | тип=…», которые сервер не сопоставил с каталогом сам. */
        public static Task match(List<String> exercises) {
            return new Task(Kind.MATCH, List.of(), List.of(), List.copyOf(exercises), List.of());
        }
        /** Подробный разбор недель weeks по каркасу; exercises — строки «название | тип=…» как в источнике. */
        public static Task weeks(ImportPlan plan, List<Integer> weeks, List<String> exercises) {
            var selected = plan.weeks().stream().filter(week -> weeks.contains(week.week())).toList();
            return new Task(Kind.WEEKS, List.copyOf(weeks), selected, List.copyOf(exercises), plan.rules());
        }
    }

    /**
     * {@code truncated} — ответ обрезан по лимиту вывода: готовые строки можно использовать, остальное — нет.
     * {@code reasoning} — рассуждения модели, если они включены: только для отладки промпта, в разбор не идут.
     */
    record Result(String text, int inputTokens, int outputTokens, int cachedTokens, boolean truncated, String reasoning) {
        public Result(String text, int inputTokens, int outputTokens, int cachedTokens, boolean truncated) {
            this(text, inputTokens, outputTokens, cachedTokens, truncated, "");
        }
    }
}
