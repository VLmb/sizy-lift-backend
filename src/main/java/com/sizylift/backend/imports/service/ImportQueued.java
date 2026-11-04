package com.sizylift.backend.imports.service;

import java.util.UUID;

/** Сессия поставлена в очередь; worker подхватывает её после commit транзакции. */
public record ImportQueued(UUID id) { }
