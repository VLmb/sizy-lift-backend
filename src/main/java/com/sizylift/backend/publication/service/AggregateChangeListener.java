package com.sizylift.backend.publication.service;

import java.util.UUID;

/**
 * Узнаёт о каждой принятой записи агрегата (v1 и v2). Вызывается последним шагом транзакции записи:
 * реализация может блокировать строки, которые все записи берут в одном порядке (лента, ADR-0007, раздел 4).
 */
public interface AggregateChangeListener {

    void changed(UUID ownerId, String type, UUID aggregateId, long revision, boolean deleted);
}
