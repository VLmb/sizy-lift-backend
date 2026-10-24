package com.sizylift.backend.publication.dto.payload;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import static com.sizylift.backend.publication.dto.payload.PayloadValues.*;

public record InjuryPayloadV1(
        @NotBlank @Size(max = SHORT_TEXT) String name,
        @Size(max = LONG_TEXT) String description,
        @NotNull @Pattern(regexp = INJURY_STATUS) String status,
        LocalDate startedOn,
        LocalDate endedOn,
        @NotNull Instant updatedAt) implements PayloadV1 {
}
