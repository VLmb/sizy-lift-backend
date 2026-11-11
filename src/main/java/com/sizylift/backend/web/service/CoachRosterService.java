package com.sizylift.backend.web.service;

import java.util.List;
import java.util.UUID;

import com.sizylift.backend.inbox.config.CoachCalendar;
import com.sizylift.backend.inbox.dto.InboxDtos.WorkoutFacts;
import com.sizylift.backend.inbox.repository.WorkoutFactsRepository;
import com.sizylift.backend.inbox.repository.WorkoutFactsRepository.WorkoutKey;
import com.sizylift.backend.web.repository.CoachRosterRepository;
import com.sizylift.backend.web.repository.CoachRosterRepository.AthleteRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CoachRosterService {
    private final CoachRosterRepository repository;
    private final WorkoutFactsRepository facts;
    private final CoachCalendar calendar;

    public CoachRosterService(CoachRosterRepository repository, WorkoutFactsRepository facts,
                              CoachCalendar calendar) {
        this.repository = repository;
        this.facts = facts;
        this.calendar = calendar;
    }

    /** Список и факты последних тренировок читаются из одного снимка. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<AthleteRow> list(UUID caller) {
        var rows = repository.list(caller, calendar.today());
        var last = facts.facts(rows.stream().filter(row -> row.workoutId() != null)
                .map(row -> new WorkoutKey(row.athleteId(), row.workoutId())).toList());
        return rows.stream().map(row -> row.workoutId() == null ? row
                : row.withFacts(WorkoutFacts.of(last.get(new WorkoutKey(row.athleteId(), row.workoutId())))))
                .toList();
    }
}
