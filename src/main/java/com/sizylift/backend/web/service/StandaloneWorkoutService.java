package com.sizylift.backend.web.service;

import java.util.HashSet;
import java.util.UUID;
import com.sizylift.backend.access.service.AccessPolicy;
import com.sizylift.backend.publication.service.AggregateType;
import com.sizylift.backend.web.dto.StandaloneWorkoutDtos.Response;
import com.sizylift.backend.web.dto.StandaloneWorkoutDtos.SaveRequest;
import com.sizylift.backend.web.repository.StandaloneWorkoutRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ResponseStatus;

@Service
public class StandaloneWorkoutService {
    private final StandaloneWorkoutRepository repository;
    private final AccessPolicy access;

    public StandaloneWorkoutService(StandaloneWorkoutRepository repository, AccessPolicy access) { this.repository = repository; this.access = access; }

    @Transactional(readOnly = true)
    public Response read(UUID caller, UUID owner, UUID id) {
        var target = owner == null ? caller : owner;
        if (!access.canRead(caller, target, AggregateType.WORKOUT)) throw new WebReadService.NotFound();
        return repository.find(target, id).orElseThrow(WebReadService.NotFound::new);
    }

    @Transactional
    public Response save(UUID caller, UUID owner, UUID id, SaveRequest request) {
        var target = writable(caller, owner);
        if (!id.equals(request.workout().id()) || repository.programIdExists(target, id)) throw new InvalidWorkout();
        validateIds(request);
        if (!repository.save(target, id, request.revision(), request.workout())) {
            var current = repository.find(target, id);
            // Повтор после потери ответа безопасен, но не перезаписывает другую правку.
            if (current.isPresent() && current.get().revision() == request.revision() + 1 && current.get().workout().equals(request.workout())) return current.get();
            throw new Conflict();
        }
        return repository.find(target, id).orElseThrow(WebReadService.NotFound::new);
    }

    @Transactional
    public void delete(UUID caller, UUID owner, UUID id, long revision) {
        var target = writable(caller, owner);
        if (!repository.delete(target, id, revision)) {
            if (repository.find(target, id).isEmpty()) throw new WebReadService.NotFound();
            throw new Conflict();
        }
    }

    private UUID writable(UUID caller, UUID owner) {
        var target = owner == null ? caller : owner;
        access.lockOwner(target);
        if (!access.canWriteFor(caller, target, AggregateType.WORKOUT)) throw new Forbidden();
        return target;
    }

    private static void validateIds(SaveRequest request) {
        var ids = new HashSet<UUID>();
        ids.add(request.workout().id());
        for (var block : request.workout().blocks()) {
            if (!ids.add(block.id())) throw new InvalidWorkout();
            for (var exercise : block.exercises()) {
                if (!ids.add(exercise.id())) throw new InvalidWorkout();
                for (var set : exercise.sets()) if (!ids.add(set.id())) throw new InvalidWorkout();
            }
        }
    }

    @ResponseStatus(HttpStatus.CONFLICT) public static class Conflict extends RuntimeException { }
    @ResponseStatus(HttpStatus.FORBIDDEN) public static class Forbidden extends RuntimeException { }
    @ResponseStatus(HttpStatus.BAD_REQUEST) public static class InvalidWorkout extends RuntimeException { }
}
