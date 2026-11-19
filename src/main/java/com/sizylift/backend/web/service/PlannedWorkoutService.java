package com.sizylift.backend.web.service;

import java.util.UUID;

import com.sizylift.backend.web.repository.DiaryReadRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PlannedWorkoutService {

    private final DiaryReadRepository diary;

    public PlannedWorkoutService(DiaryReadRepository diary) {
        this.diary = diary;
    }

    public void skip(UUID athlete, UUID plannedId) {
        if (!diary.skip(athlete, plannedId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    public void unskip(UUID athlete, UUID plannedId) {
        diary.unskip(athlete, plannedId);
    }
}
