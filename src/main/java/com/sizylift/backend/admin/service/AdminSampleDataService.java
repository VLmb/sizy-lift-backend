package com.sizylift.backend.admin.service;

import java.time.*;
import java.util.UUID;
import com.sizylift.backend.admin.repository.AdminSampleDataRepository;
import com.sizylift.backend.sync.service.SyncPushService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** Административная команда целиком атомарна, включая ревизии, ленты и маркер повтора. */
@Service
public class AdminSampleDataService {
    private static final int PLANNED_WORKOUTS = 30;
    private static final int CURRENT_WEEK = 4;
    private static final int MISSED_WORKOUTS = 1;
    private final AdminUserService users;
    private final AdminSampleDataRepository samples;
    private final AdminSampleScenario scenario;
    private final SyncPushService sync;
    private final ObjectMapper json;
    private final Clock clock;

    public AdminSampleDataService(AdminUserService users, AdminSampleDataRepository samples,
            AdminSampleScenario scenario, SyncPushService sync, ObjectMapper json, Clock clock) {
        this.users = users;
        this.samples = samples;
        this.scenario = scenario;
        this.sync = sync;
        this.json = json;
        this.clock = clock;
    }

    @Transactional
    public Result generate(String subject, String adminSubject) {
        var user = users.user(subject);
        if (!user.state().equals("active") || user.accountId() == null)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Пользователь должен завершить регистрацию и хотя бы раз войти в Sizy.");
        var owner = user.accountId();
        if (!samples.lock(owner)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var existing = samples.find(owner);
        if (existing.isPresent()) return json.readValue(existing.get(), Result.class).replay();
        var today = LocalDate.now(clock.withZone(AdminSampleScenario.ZONE));
        var dataset = scenario.build(owner, today, clock.instant());
        for (var item : dataset.items()) {
            if (samples.exists(owner, item.type(), item.id()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Идентификаторы сценария уже заняты.");
        }
        // Автором остаётся владелец; административное право не передаётся обычному sync API.
        try {
            sync.pushGeneratedForOwner(owner, dataset.items());
        } catch (SyncPushService.GeneratedDataRejectedException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Тестовые данные не созданы, изменения отменены.");
        }
        var result = new Result(AdminSampleScenario.VERSION, false, clock.instant(), today,
                dataset.finishedProgramId(), dataset.currentProgramId(), dataset.currentStart(),
                CURRENT_WEEK, PLANNED_WORKOUTS, dataset.workouts(), MISSED_WORKOUTS);
        samples.insert(owner, result.scenario(), adminSubject, json.writeValueAsString(result));
        return result;
    }

    public record Result(String scenario, boolean alreadyGenerated, Instant generatedAt, LocalDate anchorDate,
            UUID finishedProgramId, UUID currentProgramId, LocalDate currentStart, int currentWeek,
            int plannedWorkouts, int completedWorkouts, int missedWorkouts) {
        Result replay() {
            return new Result(scenario, true, generatedAt, anchorDate, finishedProgramId, currentProgramId,
                    currentStart, currentWeek, plannedWorkouts, completedWorkouts, missedWorkouts);
        }
    }
}
