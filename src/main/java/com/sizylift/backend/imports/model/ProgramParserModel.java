package com.sizylift.backend.imports.model;

import java.util.List;

/**
 * Модель, которая превращает текст программы в {@link ParsedReply} (JSON). Интерфейс позволяет сменить
 * провайдера без правок остального кода (ADR-0008). Вызывается вне транзакции БД.
 */
public interface ProgramParserModel {

    /**
     * @param input     нормализованный вход: текст или сетка таблицы
     * @param exercises названия упражнений, которые знает пользователь (каталог и свои)
     * @param dialogue  предыдущие ответы модели и ответы пользователя, по порядку
     * @param finalRound вопросы больше не задавать: принять допущения и вернуть программу
     * @throws ModelUnavailableException если провайдер недоступен или отказал
     */
    ModelReply parse(String input, List<String> exercises, List<Turn> dialogue, boolean finalRound);

    /** Реплика диалога: ответ модели или сообщение сервера (ответы пользователя, ошибки проверки). */
    record Turn(Role role, String content) {
    }

    enum Role {
        MODEL, USER
    }

    /** Сырой JSON ответа модели и расход токенов. */
    record ModelReply(String json, int tokensIn, int tokensOut) {
    }
}
