package com.sizylift.backend.publication.dto.payload;

import java.time.Instant;

/** Корень payload v1; {@code updatedAt} нужен для выбора победителя при конфликте записей. */
public interface PayloadV1 {

    Instant updatedAt();
}
