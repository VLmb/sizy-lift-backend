package com.sizylift.backend.inbox.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.sizylift.backend.inbox.config.CoachCalendar;
import com.sizylift.backend.inbox.dto.InboxDtos.EventResponse;
import com.sizylift.backend.inbox.dto.InboxDtos.InboxResponse;
import com.sizylift.backend.inbox.dto.InboxDtos.SeenRequest;
import com.sizylift.backend.inbox.dto.InboxDtos.WorkoutFacts;
import com.sizylift.backend.inbox.entity.CoachEventKind;
import com.sizylift.backend.inbox.repository.CoachEventRepository;
import com.sizylift.backend.inbox.repository.CoachEventRepository.EventRow;
import com.sizylift.backend.inbox.repository.CoachEventRepository.FeedQuery;
import com.sizylift.backend.inbox.repository.WorkoutFactsRepository;
import com.sizylift.backend.inbox.repository.WorkoutFactsRepository.Facts;
import com.sizylift.backend.inbox.repository.WorkoutFactsRepository.WorkoutKey;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Чтение и отметка «Входящих». Чужие и оборванные связи просто не попадают в выборку. */
@Service
public class CoachInboxService {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

    private final CoachEventRepository events;
    private final WorkoutFactsRepository facts;
    private final CoachCalendar calendar;

    CoachInboxService(CoachEventRepository events, WorkoutFactsRepository facts, CoachCalendar calendar) {
        this.events = events;
        this.facts = facts;
        this.calendar = calendar;
    }

    @Transactional(readOnly = true)
    public InboxResponse inbox(UUID coachId, UUID athleteId, boolean unseenOnly, Instant before, UUID beforeId,
                               int limit) {
        var rows = events.feed(new FeedQuery(coachId, athleteId, unseenOnly, before, beforeId, limit));
        var completed = facts.facts(rows.stream().filter(CoachInboxService::completed)
                .map(row -> new WorkoutKey(row.athleteId(), row.subjectId())).toList());
        return new InboxResponse(events.unseenCount(coachId, athleteId),
                rows.stream().map(row -> response(row, completed)).toList());
    }

    @Transactional
    public void seen(UUID coachId, SeenRequest request) {
        if (request == null || request.empty()) {
            throw new EmptySeenRequest();
        }
        List<UUID> ids = request.eventIds() == null || request.eventIds().isEmpty() ? null : request.eventIds();
        events.markSeen(coachId, ids, request.athleteId(), request.subjectId(), calendar.now());
    }

    private static boolean completed(EventRow row) {
        return row.kind() == CoachEventKind.WORKOUT_COMPLETED && row.subjectId() != null;
    }

    private static EventResponse response(EventRow row, Map<WorkoutKey, Facts> completed) {
        var workout = completed(row) ? completed.get(new WorkoutKey(row.athleteId(), row.subjectId())) : null;
        var comment = workout == null ? row.comment() : workout.completionComment();
        return new EventResponse(row.id(), row.athleteId(), row.athleteName(), row.kind().code(), row.subjectId(),
                row.subjectDate(), row.title(), row.occurredAt(), row.seenAt(), WorkoutFacts.of(workout), comment);
    }

    /** В теле отметки нет ни одного условия. */
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public static class EmptySeenRequest extends RuntimeException {
    }
}
