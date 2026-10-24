package com.sizylift.backend.imports.entity;

import java.util.List;

import com.sizylift.backend.imports.dto.ImportDtos.Answer;

/** Раунд диалога: сырой ответ модели и ответы пользователя на его вопросы (у последнего раунда — пусто). */
public record Round(String reply, List<Answer> answers) {
}
