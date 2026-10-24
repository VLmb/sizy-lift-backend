package com.sizylift.backend.publication.dto.payload;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import static com.sizylift.backend.publication.dto.payload.PayloadValues.*;

/** Профиль спортсмена без аватара. */
public record AthleteProfilePayloadV1(
        @Size(max = SHORT_TEXT) String displayName,
        LocalDate birthDate,
        @Pattern(regexp = SEX) String sex,
        @Min(1) Integer heightMm,
        @Min(1) Integer weightGrams,
        LocalDate trainingStartedOn,
        @NotNull @Size(max = MAX_SPORT_DIRECTIONS) List<@NotBlank @Size(max = SHORT_TEXT) String> sportDirections,
        @Size(max = LONG_TEXT) String goals,
        LocalDate goalDeadline,
        @Size(max = LONG_TEXT) String equipmentNotes,
        @Size(max = LONG_TEXT) String exercisePreferences,
        @Size(max = LONG_TEXT) String schedule,
        @Size(max = LONG_TEXT) String competitionNotes,
        @NotNull Instant updatedAt) implements PayloadV1 {
}
