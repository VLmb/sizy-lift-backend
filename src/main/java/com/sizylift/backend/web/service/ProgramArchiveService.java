package com.sizylift.backend.web.service;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import com.sizylift.backend.access.service.AccessPolicy;
import com.sizylift.backend.publication.dto.ItemStatus;
import com.sizylift.backend.publication.repository.PublishedAggregateRepository;
import com.sizylift.backend.publication.service.AggregateType;
import com.sizylift.backend.sync.dto.SyncPushRequest.Item;
import com.sizylift.backend.sync.service.SyncPushService;
import com.sizylift.backend.web.repository.ProgramArchiveRepository;
import com.sizylift.backend.web.repository.ProgramArchiveRepository.ArchivedProgram;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ResponseStatus;
import tools.jackson.databind.ObjectMapper;

/** Архивация с проверкой прочитанной ревизии; запись и снимок коммитятся вместе. */
@Service
public class ProgramArchiveService {
    private static final String CLIENT = "web-archive";
    private static final int SCHEMA = 1;
    private final ProgramArchiveRepository archive;
    private final WebReadService reads;
    private final PublishedAggregateRepository aggregates;
    private final AccessPolicy access;
    private final SyncPushService sync;
    private final ObjectMapper json;
    private final Clock clock;

    public ProgramArchiveService(ProgramArchiveRepository archive, WebReadService reads,
                                 PublishedAggregateRepository aggregates, AccessPolicy access,
                                 SyncPushService sync, ObjectMapper json, Clock clock) {
        this.archive = archive;
        this.reads = reads;
        this.aggregates = aggregates;
        this.access = access;
        this.sync = sync;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ArchivedProgram> list(UUID caller, UUID owner) {
        var target = target(caller, owner);
        return archive.list(target);
    }

    @Transactional
    public void archive(UUID caller, UUID owner, UUID id, long revision) {
        var target = target(caller, owner);
        access.lockOwner(target);
        var state = aggregates.lock(target, AggregateType.PROGRAM, id);
        if (state.deleted() || state.revision() != revision) {
            throw new Conflict();
        }
        writable(caller, target, state.authorUserId());
        var program = reads.program(caller, target, id);
        var item = new Item(AggregateType.PROGRAM, id, target, revision, clock.instant(), CLIENT,
                clock.millis(), SCHEMA, true, null);
        var result = sync.push(caller, List.of(item)).results().getFirst();
        if (result.status() != ItemStatus.ACCEPTED) {
            throw new Conflict();
        }
        archive.save(target, id, state.authorUserId(), program.program(), result.revision());
    }

    @Transactional
    public void restore(UUID caller, UUID owner, UUID id) {
        var target = target(caller, owner);
        access.lockOwner(target);
        var state = aggregates.lock(target, AggregateType.PROGRAM, id);
        var saved = archive.lock(target, id).orElseThrow(WebReadService.NotFound::new);
        writable(caller, target, state.authorUserId());
        if (!state.deleted() || state.revision() != saved.deletedRevision()) {
            throw new Conflict();
        }
        var item = new Item(AggregateType.PROGRAM, id, target, state.revision(), clock.instant(), CLIENT,
                clock.millis(), SCHEMA, false, json.valueToTree(saved.program()));
        if (sync.push(caller, List.of(item)).results().getFirst().status() != ItemStatus.ACCEPTED) {
            throw new Conflict();
        }
        archive.remove(target, id);
    }

    private UUID target(UUID caller, UUID owner) {
        var target = owner == null ? caller : owner;
        if (!access.canRead(caller, target, AggregateType.PROGRAM)) {
            throw new WebReadService.NotFound();
        }
        return target;
    }

    private void writable(UUID caller, UUID owner, UUID author) {
        if (!access.canWrite(caller, owner, AggregateType.PROGRAM, author, true)) {
            throw new Forbidden();
        }
    }

    @ResponseStatus(HttpStatus.CONFLICT)
    public static class Conflict extends RuntimeException {
    }

    @ResponseStatus(HttpStatus.FORBIDDEN)
    public static class Forbidden extends RuntimeException {
    }
}
