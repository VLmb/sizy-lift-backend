package com.sizylift.backend.publication.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.sizylift.backend.publication.dto.payload.WorkoutReschedulePayloadV1;
import com.sizylift.backend.publication.repository.BetweenDataRepository;
import com.sizylift.backend.publication.repository.PublishedAggregateRepository;
import com.sizylift.backend.publication.repository.StandaloneDataRepository;
import org.springframework.stereotype.Component;

/**
 * Агрегаты, которые зависят от других и удаляются сервером (ADR-0007, разделы 7–8):
 * <ul>
 *   <li>перенос — когда его плановой тренировки больше нет, у неё сменилась дата или удалена программа; так же
 *   у разовой тренировки (ADR-0011);</li>
 *   <li>комментарий — когда удалена его тренировка.</li>
 * </ul>
 * Перенос на исходную дату программы — тоже снятие переноса ({@link #returnsToPlan}).
 * Каскады явные, а не FK: программа и тренировка заменяются целиком, и FK удалял бы зависимые при каждой правке.
 * Вызывается в транзакции записи главного агрегата: {@link #capture} до замены данных, {@link #reconcile} после.
 * Строки зависимых блокируются после строки главного агрегата, в порядке id.
 */
@Component
public class DependentAggregates {

    /** Клиент записи tombstone, который ставит сам сервер. */
    static final String SERVER_CLIENT_ID = "server:cascade";
    static final long SERVER_CLIENT_VERSION = 1;

    private final BetweenDataRepository between;
    private final StandaloneDataRepository standalone;
    private final PublishedAggregateRepository aggregates;
    private final AggregateArbiter arbiter;

    DependentAggregates(BetweenDataRepository between, StandaloneDataRepository standalone,
                        PublishedAggregateRepository aggregates, AggregateArbiter arbiter) {
        this.between = between;
        this.standalone = standalone;
        this.aggregates = aggregates;
        this.arbiter = arbiter;
    }

    /** Состояние, нужное для каскада: даты плановых тренировок программы до её замены. */
    public Snapshot capture(UUID ownerId, String type, UUID aggregateId) {
        return switch (type) {
            case AggregateType.PROGRAM -> new Snapshot(between.plannedWorkoutDates(ownerId, aggregateId));
            case AggregateType.STANDALONE_WORKOUT -> new Snapshot(standalone.workoutDate(ownerId, aggregateId)
                    .map(date -> Map.of(aggregateId, date)).orElse(Map.of()));
            default -> Snapshot.EMPTY;
        };
    }

    /**
     * Перенос возвращает тренировку на её дату в программе: переноса больше нет, запись сохраняется как
     * tombstone. Плановой тренировки нет — обычный перенос.
     */
    public boolean returnsToPlan(UUID ownerId, UUID plannedWorkoutId, WorkoutReschedulePayloadV1 reschedule) {
        var planned = reschedule.programId() == null
                ? standalone.workoutDate(ownerId, plannedWorkoutId)
                : between.plannedWorkoutDate(ownerId, reschedule.programId(), plannedWorkoutId);
        return planned.filter(reschedule.scheduledDate()::equals).isPresent();
    }

    /** Удаляет ставшие недействительными зависимые агрегаты; возвращает их для рассылки {@code delete}. */
    public List<Removed> reconcile(UUID ownerId, String type, UUID aggregateId, boolean deleted, Snapshot before) {
        return switch (type) {
            case AggregateType.PROGRAM -> remove(ownerId, AggregateType.WORKOUT_RESCHEDULE,
                    staleReschedules(ownerId, aggregateId, deleted, before));
            case AggregateType.WORKOUT -> deleted
                    ? remove(ownerId, AggregateType.WORKOUT_COMMENT, between.commentsOf(ownerId, aggregateId))
                    : List.of();
            case AggregateType.STANDALONE_WORKOUT -> remove(ownerId, AggregateType.WORKOUT_RESCHEDULE,
                    staleStandaloneReschedule(ownerId, aggregateId, deleted, before));
            default -> List.of();
        };
    }

    /**
     * Перенос снимается, если плановой тренировки нет или её дату поменял автор программы: действует последнее
     * изменение. Перенос тренировки, которой не было до записи, не трогается, пока она есть.
     */
    private List<UUID> staleReschedules(UUID ownerId, UUID programId, boolean deleted, Snapshot before) {
        var reschedules = between.reschedulesOf(ownerId, programId);
        if (deleted || reschedules.isEmpty()) {
            return reschedules;
        }
        var after = between.plannedWorkoutDates(ownerId, programId);
        return reschedules.stream()
                .filter(id -> !after.containsKey(id)
                        || before.dates().containsKey(id) && !before.dates().get(id).equals(after.get(id)))
                .toList();
    }

    /** Перенос разовой снимается, когда её удалили или автор сменил её дату. */
    private List<UUID> staleStandaloneReschedule(UUID ownerId, UUID workoutId, boolean deleted, Snapshot before) {
        if (between.findReschedule(ownerId, workoutId).isEmpty()) {
            return List.of();
        }
        var after = standalone.workoutDate(ownerId, workoutId);
        var moved = before.dates().containsKey(workoutId) && after.isPresent()
                && !before.dates().get(workoutId).equals(after.get());
        return deleted || after.isEmpty() || moved ? List.of(workoutId) : List.of();
    }

    private List<Removed> remove(UUID ownerId, String type, List<UUID> ids) {
        var removed = new ArrayList<Removed>();
        ids.stream().sorted(Comparator.comparing(UUID::toString)).forEach(id -> {
            var state = aggregates.lock(ownerId, type, id);
            if (!state.exists() || state.deleted()) {
                return;
            }
            delete(ownerId, type, id);
            var revision = aggregates.storeSynced(ownerId, type, id, true, arbiter.effectiveChangedAt(null),
                    SERVER_CLIENT_ID, SERVER_CLIENT_VERSION);
            removed.add(new Removed(type, id, revision));
        });
        return removed;
    }

    private void delete(UUID ownerId, String type, UUID id) {
        if (AggregateType.WORKOUT_RESCHEDULE.equals(type)) {
            between.deleteReschedule(ownerId, id);
        } else {
            between.deleteComment(ownerId, id);
        }
    }

    public record Snapshot(Map<UUID, LocalDate> dates) {

        static final Snapshot EMPTY = new Snapshot(Map.of());
    }

    /** Зависимый агрегат, удалённый сервером: tombstone с новой ревизией. */
    public record Removed(String type, UUID id, long revision) {
    }
}
