package com.sizylift.backend.inbox.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** «Входящие» тренера (docs/coach-workspace.md, раздел 4). */
public final class InboxDtos {

    private InboxDtos() {
    }

    public record InboxResponse(long unseenCount, List<EventResponse> events) {
    }

    public record EventResponse(UUID id, UUID athleteId, String athleteName, String kind, UUID subjectId,
                                LocalDate subjectDate, String title, Instant occurredAt, Instant seenAt) {
    }

    /** Хотя бы одно поле; заданные поля сужают выборку вместе. */
    public record SeenRequest(List<UUID> eventIds, UUID athleteId, UUID subjectId, Boolean all) {

        public boolean empty() {
            return (eventIds == null || eventIds.isEmpty()) && athleteId == null && subjectId == null
                    && !Boolean.TRUE.equals(all);
        }
    }
}
