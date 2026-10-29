package com.sizylift.backend.inbox.service;

import java.util.UUID;

import com.sizylift.backend.inbox.config.CoachCalendar;
import com.sizylift.backend.inbox.entity.CoachEventKind;
import com.sizylift.backend.inbox.repository.CoachEventRepository;
import com.sizylift.backend.publication.service.AggregateChange;
import com.sizylift.backend.publication.service.AggregateType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Пишет события «Входящих» в транзакции записи данных (v1 и v2 — через фан-аут, связь — при принятии
 * приглашения). Нет коммита — нет события. Событие получает только активная связь подопечного.
 * <ul>
 *   <li>новая тренировка подопечного — {@code workout_completed}; её удаление убирает событие;</li>
 *   <li>перенос подопечного — {@code workout_rescheduled}; отмена переноса убирает событие.</li>
 * </ul>
 */
@Service
public class CoachInboxRecorder {

    private final CoachEventRepository events;
    private final CoachCalendar calendar;

    CoachInboxRecorder(CoachEventRepository events, CoachCalendar calendar) {
        this.events = events;
        this.calendar = calendar;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void inviteAccepted(UUID linkId) {
        events.inviteAccepted(linkId, calendar.now());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void onChange(AggregateChange change) {
        switch (change.type()) {
            case AggregateType.WORKOUT -> onWorkout(change);
            case AggregateType.WORKOUT_RESCHEDULE -> onReschedule(change);
            default -> {
            }
        }
    }

    private void onWorkout(AggregateChange change) {
        if (change.deleted()) {
            events.deleteBySubject(change.ownerId(), CoachEventKind.WORKOUT_COMPLETED, change.aggregateId());
        } else if (change.byOwner() && change.created()) {
            events.workoutCompleted(change.ownerId(), change.aggregateId(), calendar.now());
        }
    }

    private void onReschedule(AggregateChange change) {
        if (change.deleted()) {
            events.deleteBySubject(change.ownerId(), CoachEventKind.WORKOUT_RESCHEDULED, change.aggregateId());
        } else if (change.byOwner()) {
            events.workoutRescheduled(change.ownerId(), change.aggregateId(), calendar.now());
        }
    }
}
