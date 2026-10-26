package com.sizylift.backend.web.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.sizylift.backend.publication.dto.payload.ProgramPayloadV1;
import com.sizylift.backend.publication.dto.payload.PayloadV1;
import com.sizylift.backend.publication.dto.payload.WorkoutPayloadV1;

/** Ответы чтения для веба (docs/web.md). Записывает веб через синхронизацию v2. */
public final class WebDtos {

    private WebDtos() {
    }

    /** Справочник: категория движения, оборудование или группа мышц. */
    public record Reference(UUID id, String code, String name) {
    }

    /** Мышца упражнения: {@code role} — {@code primary} или {@code secondary}. */
    public record MuscleRef(UUID muscleGroupId, String role) {
    }

    /**
     * Упражнение каталога ({@code ownerId == null}) или своё. {@code revision} — ревизия агрегата своего
     * упражнения для {@code baseRevision} при правке; у системных {@code null}.
     */
    public record CatalogExercise(UUID id, UUID ownerId, String name, String shortName, UUID movementCategoryId,
                                  String resultType, boolean isSbd, String note, List<MuscleRef> muscles,
                                  List<UUID> equipmentIds, Long revision) {
    }

    public record CatalogResponse(List<Reference> categories, List<Reference> equipment, List<Reference> muscles,
                                  List<CatalogExercise> exercises) {
    }

    /** Строка списка программ; {@code authorId} — тренер, если программу пишет он, иначе {@code null}. */
    public record ProgramSummary(UUID id, String name, String description, LocalDate startDate, LocalDate endDate,
                                 String status, int weeks, int workouts, Instant updatedAt, long revision,
                                 UUID authorId, Instant changedAt) {
    }

    public record ProgramListResponse(UUID ownerId, List<ProgramSummary> programs) {
    }

    /** Программа целиком в формате payload v1 и состояние агрегата для следующей записи. */
    public record ProgramResponse(UUID ownerId, UUID id, long revision, UUID authorId, Instant changedAt,
                                  ProgramPayloadV1 program) {
    }

    public record OneRepMax(UUID exerciseId, int weightGrams, LocalDate achievedOn, Instant updatedAt,
                            long revision) {
    }

    public record OneRepMaxListResponse(UUID ownerId, List<OneRepMax> oneRepMaxes) {
    }

    /** Выполненный подход в истории упражнения; единицы как в payload v1. */
    public record HistorySet(String setType, Integer weightGrams, Integer repetitions, Integer durationMs,
                             Integer distanceM, Integer rpeTenths) {
    }

    public record HistoryEntry(UUID workoutId, LocalDate workoutDate, String title, List<HistorySet> sets) {
    }

    public record ExerciseHistoryResponse(UUID ownerId, UUID exerciseId, List<HistoryEntry> entries) {
    }

    public record AggregateResponse(UUID id, long revision, UUID authorId, PayloadV1 payload) {
    }

    public record WorkoutSummary(UUID id, LocalDate workoutDate, String title, Instant startedAt,
                                 Instant completedAt, String quality, UUID plannedWorkoutId, int recordCount) {
    }

    public record PlannedSummary(UUID id, UUID programId, String title, LocalDate originalDate,
                                 LocalDate scheduledDate, Instant rescheduledAt, UUID workoutId, LocalDate linkedWorkoutDate) {
    }

    public record TimelineResponse(UUID ownerId, List<PlannedSummary> planned, List<WorkoutSummary> workouts) {
    }

    public record WorkoutResponse(UUID ownerId, UUID id, long revision, WorkoutPayloadV1 workout,
                                  List<AggregateResponse> comments) {
    }

    public record ProfileResponse(UUID ownerId, AggregateResponse profile, boolean healthAccess,
                                  List<AggregateResponse> injuries, List<AggregateResponse> contraindications) {
    }
}
