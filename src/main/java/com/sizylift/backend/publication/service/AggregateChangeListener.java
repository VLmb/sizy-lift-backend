package com.sizylift.backend.publication.service;

import java.util.UUID;

/**
 * Узнаёт о записях агрегатов (v1 и v2). Обе точки записи вызывают {@link #beforeWrite} первым шагом
 * транзакции, а {@link #changed} — последним, после принятой записи. Так все записи берут блокировки в одном
 * порядке: владелец, агрегат, ленты (ADR-0007, разделы 4–5).
 */
public interface AggregateChangeListener {

    /** Перед блокировкой агрегата: связи владельца не должны меняться до коммита записи. */
    void beforeWrite(UUID ownerId);

    void changed(UUID ownerId, String type, UUID aggregateId, long revision, boolean deleted);
}
