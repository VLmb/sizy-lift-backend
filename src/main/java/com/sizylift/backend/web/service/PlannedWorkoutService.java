package com.sizylift.backend.web.service;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import com.sizylift.backend.publication.dto.ItemStatus;
import com.sizylift.backend.publication.dto.payload.WorkoutReschedulePayloadV1;
import com.sizylift.backend.publication.repository.PublishedAggregateRepository;
import com.sizylift.backend.publication.service.AggregateType;
import com.sizylift.backend.sync.dto.SyncPushRequest.Item;
import com.sizylift.backend.sync.service.SyncPushService;
import com.sizylift.backend.web.repository.DiaryReadRepository;
import com.sizylift.backend.web.repository.DiaryReadRepository.SkipTarget;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/**
 * Отметка пропуска в вебе (PWA-09) — та же запись агрегата {@code workout_reschedule}, что у телефона, через
 * sync v2: отметку видят телефон спортсмена и тренер, а тренеру приходит уведомление.
 */
@Service
public class PlannedWorkoutService {

    private static final String CLIENT_PREFIX = "web-skip:";
    private static final int SCHEMA_VERSION = 1;

    private final DiaryReadRepository diary;
    private final SyncPushService push;
    private final PublishedAggregateRepository aggregates;
    private final ObjectMapper json;
    private final Clock clock;

    public PlannedWorkoutService(DiaryReadRepository diary, SyncPushService push,
                                 PublishedAggregateRepository aggregates, ObjectMapper json, Clock clock) {
        this.diary = diary;
        this.push = push;
        this.aggregates = aggregates;
        this.json = json;
        this.clock = clock;
    }

    public void skip(UUID athlete, UUID plannedId) {
        var target = diary.skipTarget(athlete, plannedId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!target.skipped()) {
            write(athlete, plannedId, target, true);
        }
    }

    /** Снятие отметки; без переноса агрегат удаляется сам — перенос на дату плана (docs/coaching.md). */
    public void unskip(UUID athlete, UUID plannedId) {
        diary.removeLegacySkip(athlete, plannedId);
        diary.skipTarget(athlete, plannedId)
                .filter(SkipTarget::skipped)
                .ifPresent(target -> write(athlete, plannedId, target, false));
    }

    private void write(UUID athlete, UUID plannedId, SkipTarget target, boolean skipped) {
        var now = clock.instant();
        var payload = new WorkoutReschedulePayloadV1(target.programId(), target.scheduledDate(), now, skipped);
        var clientId = CLIENT_PREFIX + athlete;
        var version = aggregates.clientVersion(athlete, AggregateType.WORKOUT_RESCHEDULE, plannedId, clientId) + 1;
        var item = new Item(AggregateType.WORKOUT_RESCHEDULE, plannedId, null, target.revision(), now, clientId,
                version, SCHEMA_VERSION, false, json.valueToTree(payload));
        var result = push.push(athlete, List.of(item)).results().getFirst();
        if (result.status() == ItemStatus.REJECTED) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }
}
