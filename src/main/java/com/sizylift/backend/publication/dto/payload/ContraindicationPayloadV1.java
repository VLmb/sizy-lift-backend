package com.sizylift.backend.publication.dto.payload;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import static com.sizylift.backend.publication.dto.payload.PayloadValues.LONG_TEXT;
import static com.sizylift.backend.publication.dto.payload.PayloadValues.SHORT_TEXT;

public record ContraindicationPayloadV1(
        @NotBlank @Size(max = SHORT_TEXT) String name,
        @Size(max = LONG_TEXT) String description,
        @NotNull Instant updatedAt) {
}
