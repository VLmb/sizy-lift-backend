package com.sizylift.backend.publication.service;

import jakarta.validation.Validator;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Разбирает и проверяет payload агрегата; общий для публикации v1 и sync v2. */
@Component
public class PayloadParser {

    private final ObjectMapper json;
    private final Validator validator;

    PayloadParser(ObjectMapper json, Validator validator) {
        this.json = json;
        this.validator = validator;
    }

    /** @throws InvalidPayloadException если payload не объект, не соответствует типу или не проходит валидацию */
    public <P> P parse(JsonNode payload, Class<P> type) {
        if (payload == null || !payload.isObject()) {
            throw new InvalidPayloadException();
        }
        P value;
        try {
            value = json.treeToValue(payload, type);
        } catch (JacksonException e) {
            throw new InvalidPayloadException();
        }
        if (!validator.validate(value).isEmpty()) {
            throw new InvalidPayloadException();
        }
        return value;
    }

    public static class InvalidPayloadException extends RuntimeException {
    }
}
