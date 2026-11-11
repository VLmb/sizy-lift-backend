package com.sizylift.backend.inbox.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.sizylift.backend.inbox.repository.WorkoutFactsRepository.Facts;
import com.sizylift.backend.inbox.repository.WorkoutFactsRepository.RecordFact;

/** «Входящие» тренера (docs/coach-workspace.md, раздел 4). */
public final class InboxDtos {

    private InboxDtos() {
    }

    public record InboxResponse(long unseenCount, List<EventResponse> events) {
    }

    /**
     * {@code facts} — суть тренировки у {@code workout_completed}, иначе {@code null}. {@code comment} — текст
     * комментария: у {@code comment_added} — этого комментария, у {@code workout_completed} — оставленного
     * при завершении; иначе {@code null}.
     */
    public record EventResponse(UUID id, UUID athleteId, String athleteName, String kind, UUID subjectId,
                                LocalDate subjectDate, String title, Instant occurredAt, Instant seenAt,
                                WorkoutFacts facts, String comment) {
    }

    /**
     * Суть выполненной тренировки (docs/coach-workspace.md, раздел 7): подходов в плане и выполненных из них,
     * оценка, самый значимый рекорд, есть ли комментарий подопечного. Клиент сам решает, что показать.
     */
    public record WorkoutFacts(int plannedSets, int doneSets, String quality, RecordFact record,
                               boolean hasComment) {

        public static WorkoutFacts of(Facts facts) {
            return facts == null ? null : new WorkoutFacts(facts.plannedSets(), facts.doneSets(), facts.quality(),
                    facts.record(), facts.hasComment());
        }
    }

    /** Хотя бы одно поле; заданные поля сужают выборку вместе. */
    public record SeenRequest(List<UUID> eventIds, UUID athleteId, UUID subjectId, Boolean all) {

        public boolean empty() {
            return (eventIds == null || eventIds.isEmpty()) && athleteId == null && subjectId == null
                    && !Boolean.TRUE.equals(all);
        }
    }
}
