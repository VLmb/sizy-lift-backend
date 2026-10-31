package com.sizylift.backend.web.service;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import com.sizylift.backend.access.service.AccessPolicy;
import com.sizylift.backend.publication.dto.ItemStatus;
import com.sizylift.backend.publication.dto.RejectionReason;
import com.sizylift.backend.publication.dto.payload.StandaloneWorkoutPayloadV1;
import com.sizylift.backend.publication.repository.PublishedAggregateRepository;
import com.sizylift.backend.publication.service.AggregateType;
import com.sizylift.backend.sync.dto.SyncPushRequest.Item;
import com.sizylift.backend.sync.dto.SyncPushResponse.ItemResult;
import com.sizylift.backend.sync.service.SyncPushService;
import com.sizylift.backend.web.dto.StandaloneWorkoutDtos.Response;
import com.sizylift.backend.web.dto.StandaloneWorkoutDtos.SaveRequest;
import com.sizylift.backend.web.repository.StandaloneWorkoutRepository;
import com.sizylift.backend.web.repository.StandaloneWorkoutRepository.State;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ResponseStatus;
import tools.jackson.databind.ObjectMapper;

/**
 * Веб-ресурс разовой тренировки поверх sync v2 (ADR-0011): та же запись, права, ленты и уведомления, что у
 * телефона. Ревизия — серверная ревизия агрегата; запись не от текущей ревизии — 409, кроме точного повтора.
 */
@Service
public class StandaloneWorkoutService {
    private static final String CLIENT_PREFIX = "web-rest:";
    private static final int SCHEMA_VERSION = 1;

    private final StandaloneWorkoutRepository repository;
    private final AccessPolicy access;
    private final SyncPushService push;
    private final ObjectMapper json;
    private final Clock clock;
    private final PublishedAggregateRepository aggregates;

    public StandaloneWorkoutService(StandaloneWorkoutRepository repository, AccessPolicy access,
                                    SyncPushService push, ObjectMapper json, Clock clock,
                                    PublishedAggregateRepository aggregates) {
        this.repository = repository;
        this.access = access;
        this.push = push;
        this.json = json;
        this.clock = clock;
        this.aggregates = aggregates;
    }

    @Transactional(readOnly = true)
    public Response read(UUID caller, UUID owner, UUID id) {
        var target = owner == null ? caller : owner;
        if (!access.canRead(caller, target, AggregateType.STANDALONE_WORKOUT)) throw new WebReadService.NotFound();
        return repository.find(target, id).orElseThrow(WebReadService.NotFound::new);
    }

    public Response save(UUID caller, UUID owner, UUID id, SaveRequest request) {
        if (!id.equals(request.workout().id())) throw new InvalidWorkout();
        var target = owner == null ? caller : owner;
        var state = repository.state(target, id);
        checkWrite(caller, target, state);
        long current = state.map(State::revision).orElse(0L);
        if (request.revision().longValue() != current) {
            // Повтор после потери ответа безопасен, но не перезаписывает другую правку.
            var replay = repository.find(target, id)
                    .filter(saved -> saved.workout().equals(request.workout()));
            return replay.orElseThrow(Conflict::new);
        }
        var payload = new StandaloneWorkoutPayloadV1(request.workout(), clock.instant());
        outcome(write(caller, owner, id, current, false, payload));
        return repository.find(target, id).orElseThrow(Conflict::new);
    }

    public void delete(UUID caller, UUID owner, UUID id, long revision) {
        var target = owner == null ? caller : owner;
        var state = repository.state(target, id);
        checkWrite(caller, target, state);
        if (state.isEmpty() || state.get().deleted()) throw new WebReadService.NotFound();
        if (state.get().revision() != revision) throw new Conflict();
        outcome(write(caller, owner, id, revision, true, null));
    }

    private void checkWrite(UUID caller, UUID target, java.util.Optional<State> state) {
        var type = AggregateType.STANDALONE_WORKOUT;
        if (!access.canWriteFor(caller, target, type)
                || state.isPresent() && !access.canWrite(caller, target, type, state.get().authorId(), true)) {
            throw new Forbidden();
        }
    }

    private ItemResult write(UUID caller, UUID owner, UUID id, long baseRevision, boolean deleted,
                             StandaloneWorkoutPayloadV1 payload) {
        var target = owner == null ? caller : owner;
        var clientId = CLIENT_PREFIX + caller;
        // Следующая версия этого веб-клиента по агрегату: гонка двух вкладок даст stale → 409.
        var version = aggregates.clientVersion(target, AggregateType.STANDALONE_WORKOUT, id, clientId) + 1;
        var item = new Item(AggregateType.STANDALONE_WORKOUT, id, owner, baseRevision, clock.instant(), clientId,
                version, SCHEMA_VERSION, deleted, payload == null ? null : json.valueToTree(payload));
        return push.push(caller, List.of(item)).results().getFirst();
    }

    private static void outcome(ItemResult result) {
        if (result.status() == ItemStatus.ACCEPTED) return;
        if (result.status() == ItemStatus.REJECTED) {
            if (result.error() == RejectionReason.FORBIDDEN) throw new Forbidden();
            throw new InvalidWorkout();
        }
        throw new Conflict();
    }

    @ResponseStatus(HttpStatus.CONFLICT) public static class Conflict extends RuntimeException { }
    @ResponseStatus(HttpStatus.FORBIDDEN) public static class Forbidden extends RuntimeException { }
    @ResponseStatus(HttpStatus.BAD_REQUEST) public static class InvalidWorkout extends RuntimeException { }
}
