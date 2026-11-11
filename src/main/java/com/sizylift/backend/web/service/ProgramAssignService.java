package com.sizylift.backend.web.service;

import java.util.HashSet;
import java.util.UUID;

import com.sizylift.backend.coaching.service.CoachProgramCopies;
import com.sizylift.backend.sync.service.SyncPushService;
import com.sizylift.backend.web.dto.AssignDtos.AssignRequest;
import com.sizylift.backend.web.dto.AssignDtos.AssignResponse;
import com.sizylift.backend.web.dto.AssignDtos.Created;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Копии-черновики программы нескольким подопечным одной транзакцией: все или ни одной. Черновики тихие —
 * подопечный узнаёт о программе, когда тренер сделает её активной и отправит.
 */
@Service
public class ProgramAssignService {

    private static final String STATUS = "status";
    private static final String DRAFT = "draft";

    private final CoachProgramCopies copies;
    private final TransactionTemplate transactions;

    ProgramAssignService(CoachProgramCopies copies, TransactionTemplate transactions) {
        this.copies = copies;
        this.transactions = transactions;
    }

    public AssignResponse assign(UUID coachId, AssignRequest request) {
        var athletes = new HashSet<UUID>();
        for (var copy : request.copies()) {
            var status = copy.program().get(STATUS);
            if (!athletes.add(copy.athleteId()) || status == null || !DRAFT.equals(status.asString())) {
                throw new InvalidAssign();
            }
        }
        try {
            return transactions.execute(tx -> new AssignResponse(request.copies().stream()
                    .map(copy -> new Created(copy.athleteId(), copy.id(),
                            copies.create(coachId, copy.athleteId(), copy.id(), copy.program())))
                    .toList()));
        } catch (SyncPushService.AtomicCreateRejected e) {
            throw new AssignRejected();
        }
    }

    /** Повтор подопечного или не черновик. */
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public static class InvalidAssign extends RuntimeException {
    }

    /** Нет активной связи с одним из подопечных, id уже занят или payload не прошёл проверку — ничего не создано. */
    @ResponseStatus(HttpStatus.CONFLICT)
    public static class AssignRejected extends RuntimeException {
    }
}
