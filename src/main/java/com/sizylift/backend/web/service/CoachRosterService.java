package com.sizylift.backend.web.service;

import java.util.List;
import java.util.UUID;

import com.sizylift.backend.web.repository.CoachRosterRepository;
import com.sizylift.backend.web.repository.CoachRosterRepository.AthleteRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CoachRosterService {
    private final CoachRosterRepository repository;

    public CoachRosterService(CoachRosterRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<AthleteRow> list(UUID caller) {
        return repository.list(caller);
    }

    @Transactional
    public void seen(UUID caller, UUID linkId) {
        if (!repository.seen(caller, linkId)) {
            throw new WebReadService.NotFound();
        }
    }
}
