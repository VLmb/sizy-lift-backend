package com.sizylift.backend.web.service;

import java.util.List;
import java.util.UUID;

import com.sizylift.backend.inbox.config.CoachCalendar;
import com.sizylift.backend.web.repository.CoachRosterRepository;
import com.sizylift.backend.web.repository.CoachRosterRepository.AthleteRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CoachRosterService {
    private final CoachRosterRepository repository;
    private final CoachCalendar calendar;

    public CoachRosterService(CoachRosterRepository repository, CoachCalendar calendar) {
        this.repository = repository;
        this.calendar = calendar;
    }

    @Transactional(readOnly = true)
    public List<AthleteRow> list(UUID caller) {
        return repository.list(caller, calendar.today());
    }
}
