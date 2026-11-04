package com.sizylift.backend.imports.service;

import java.util.HashMap;
import java.util.Map;

import com.sizylift.backend.imports.model.ProgramParserModel;

/**
 * Считает законченные объекты в массивах верхнего уровня JSON, который приходит фрагментами:
 * например, сколько недель модель уже полностью выписала в {@code "weeks"}. Не разбирает документ
 * и не проверяет его — только честный счётчик для прогресса; проверка остаётся за валидатором.
 */
final class StreamCounter implements ProgramParserModel.Listener {
    /** Глубина корня — 1, массива верхнего уровня — 2; закрытие его элемента возвращает глубину к 2. */
    private static final int ROOT = 1;
    private static final int ARRAY = 2;
    private final Map<String, Integer> counts = new HashMap<>();
    private final StringBuilder string = new StringBuilder();
    private int depth;
    private boolean quoted;
    private boolean escaped;
    private String lastString = "";
    private String key = "";
    private final Runnable changed;

    StreamCounter(Runnable changed) { this.changed = changed; }

    /** Уведомление идёт вне блокировки счётчика: трекер под своей блокировкой читает все счётчики. */
    @Override
    public void onArguments(String delta) {
        boolean updated = false;
        synchronized (this) {
            for (int i = 0; i < delta.length(); i++) updated |= accept(delta.charAt(i));
        }
        if (updated) changed.run();
    }

    synchronized int count(String array) { return counts.getOrDefault(array, 0); }

    private boolean accept(char value) {
        if (quoted) {
            if (escaped) escaped = false;
            else if (value == '\\') escaped = true;
            else if (value == '"') { quoted = false; lastString = string.toString(); }
            else if (depth == ROOT) string.append(value);
            return false;
        }
        switch (value) {
            case '"' -> { quoted = true; string.setLength(0); }
            case ':' -> { if (depth == ROOT) key = lastString; }
            case '{', '[' -> depth++;
            case '}', ']' -> {
                depth--;
                if (value == '}' && depth == ARRAY) {
                    counts.merge(key, 1, Integer::sum);
                    return true;
                }
            }
            default -> { }
        }
        return false;
    }
}
