package com.sizylift.backend.publication.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** What the user allows to keep on the server. Injuries and contraindications ("health") are off by default. */
public record PublicationScopeDto(
        boolean enabled,
        boolean workouts,
        LocalDate workoutsSince,
        @NotNull @Pattern(regexp = "none|active|all") String programs,
        boolean profile,
        boolean records,
        boolean health) {
}
