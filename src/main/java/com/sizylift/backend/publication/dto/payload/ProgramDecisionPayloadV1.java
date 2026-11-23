package com.sizylift.backend.publication.dto.payload;

import java.time.Instant;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** Ответ подопечного на программу тренера. Id агрегата совпадает с id программы. */
public record ProgramDecisionPayloadV1(
        @NotNull @Pattern(regexp = "pending|accepted|declined") String decision,
        @NotNull Instant updatedAt) implements PayloadV1 {

    public static final String PENDING = "pending";
}
