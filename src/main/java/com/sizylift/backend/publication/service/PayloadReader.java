package com.sizylift.backend.publication.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.sizylift.backend.publication.dto.payload.PayloadV1;
import org.springframework.stereotype.Component;

/** Текущее содержимое агрегата по типу; пусто, если агрегата нет или он удалён. */
@Component
public class PayloadReader {

    private final Map<String, AggregateReader<?>> readers;

    PayloadReader(List<AggregateReader<?>> readers) {
        this.readers = readers.stream()
                .collect(Collectors.toUnmodifiableMap(AggregateReader::type, Function.identity()));
    }

    /** @throws IllegalArgumentException для неизвестного типа: в БД такие не попадают */
    public Optional<PayloadV1> read(UUID ownerId, String type, UUID aggregateId) {
        var reader = readers.get(type);
        if (reader == null) {
            throw new IllegalArgumentException("No reader for aggregate type");
        }
        return reader.read(ownerId, aggregateId).map(PayloadV1.class::cast);
    }
}
