package com.sizylift.backend.publication.dto.payload;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.sizylift.backend.publication.entity.LiftClass;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import static com.sizylift.backend.publication.dto.payload.PayloadValues.*;

/**
 * Собственное упражнение пользователя; системные не публикуются — каталог есть на сервере.
 * {@code isSbd} — поле переходного периода для телефона: в ответах равно {@code liftClass == sbd},
 * при записи учитывается только без {@code liftClass} (docs/coach-workspace.md, раздел 1).
 */
public record ExercisePayloadV1(
        @NotBlank @Size(max = SHORT_TEXT) String name,
        @Size(max = SHORT_TEXT) String shortName,
        UUID movementCategoryId,
        @NotNull @Pattern(regexp = RESULT_TYPE) String resultType,
        boolean isSbd,
        LiftClass liftClass,
        @Size(max = LONG_TEXT) String note,
        @NotNull Instant updatedAt,
        @NotNull @Size(max = MAX_MUSCLES) List<@NotNull @Valid Muscle> muscles,
        @NotNull @Size(max = MAX_EQUIPMENT) List<@NotNull UUID> equipmentIds) implements PayloadV1 {

    /** Сохранённое упражнение: {@code isSbd} выводится из класса. */
    public static ExercisePayloadV1 stored(String name, String shortName, UUID movementCategoryId, String resultType,
                                           LiftClass liftClass, String note, Instant updatedAt,
                                           List<Muscle> muscles, List<UUID> equipmentIds) {
        return new ExercisePayloadV1(name, shortName, movementCategoryId, resultType, liftClass == LiftClass.SBD,
                liftClass, note, updatedAt, muscles, equipmentIds);
    }

    /**
     * Класс для записи: явный {@code liftClass}; без него {@code isSbd = true} даёт {@code sbd} (если тип результата
     * это допускает), иначе остаётся прежний класс, а прежний {@code sbd} становится {@code other}.
     *
     * @param previous класс уже сохранённого упражнения или {@code null} для нового
     */
    public LiftClass liftClassToStore(LiftClass previous) {
        if (liftClass != null) {
            return liftClass;
        }
        if (isSbd && LiftClass.SBD.allows(resultType)) {
            return LiftClass.SBD;
        }
        return previous == null || previous == LiftClass.SBD || !previous.allows(resultType)
                ? LiftClass.OTHER : previous;
    }

    /** То же упражнение с классом {@code value} и согласованным {@code isSbd}. */
    public ExercisePayloadV1 withLiftClass(LiftClass value) {
        return stored(name, shortName, movementCategoryId, resultType, value, note, updatedAt, muscles,
                equipmentIds);
    }

    /**
     * Группы мышц (PWA-08): не больше {@value PayloadValues#MAX_MUSCLES_PER_ROLE} основных и столько же побочных,
     * одна группа — в одной роли.
     */
    @JsonIgnore
    @AssertTrue(message = "At most two primary and two secondary muscle groups, each group in one role.")
    public boolean isMuscleRolesValid() {
        if (muscles == null || muscles.stream().anyMatch(Objects::isNull)) {
            return true;
        }
        var groups = muscles.stream().map(Muscle::muscleGroupId).distinct().count();
        return groups == muscles.size() && muscles.stream()
                .collect(Collectors.groupingBy(Muscle::role, Collectors.counting()))
                .values().stream().allMatch(count -> count <= MAX_MUSCLES_PER_ROLE);
    }

    public record Muscle(@NotNull UUID muscleGroupId, @NotNull @Pattern(regexp = MUSCLE_ROLE) String role) {
    }
}
