package com.sizylift.backend.imports.service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.UUID;
import java.util.stream.Stream;

import com.sizylift.backend.access.service.AccessPolicy;
import com.sizylift.backend.imports.dto.ImportDtos.Commit;
import com.sizylift.backend.imports.dto.ImportDtos.Receipt;
import com.sizylift.backend.imports.repository.ImportRepository;
import com.sizylift.backend.publication.service.AggregateType;
import com.sizylift.backend.sync.dto.SyncPushRequest.Item;
import com.sizylift.backend.sync.service.SyncPushService;
import com.sizylift.backend.web.service.WebReadService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
public class ImportCommitService {
    private static final int SCHEMA_VERSION = 1;
    private final ImportService imports;
    private final ImportRepository sessions;
    private final ProgramDraftCompiler compiler;
    private final WebReadService reads;
    private final SyncPushService sync;
    private final AccessPolicy access;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final Clock clock;

    public ImportCommitService(ImportService imports, ImportRepository sessions, ProgramDraftCompiler compiler,
            WebReadService reads, SyncPushService sync, AccessPolicy access, TransactionTemplate transactions,
            ObjectMapper json, Clock clock) {
        this.imports = imports; this.sessions = sessions; this.compiler = compiler; this.reads = reads;
        this.sync = sync; this.access = access; this.transactions = transactions; this.json = json; this.clock = clock;
    }

    public Receipt commit(UUID caller, UUID id, Commit request) {
        var head = imports.owned(caller, id, false);
        return transactions.execute(status -> {
            // Аккаунты блокируются раньше строки сессии: удаление аккаунта идёт в том же порядке.
            Stream.of(caller, head.ownerId()).distinct().sorted(AccessPolicy.LOCK_ORDER).forEach(access::lockOwner);
            var session = imports.owned(caller, id, true);
            if (session.status().equals("committed")) return ImportService.receipt(session);
            if (!session.status().equals("review") || session.version() != request.version()) throw ImportFailure.conflict();
            if (!access.canWriteFor(caller, session.ownerId(), AggregateType.PROGRAM)) throw ImportFailure.missing();
            var at = clock.instant();
            var compiled = compiler.compile(session, at, reads.catalog(caller, session.ownerId()).exercises());
            var items = new ArrayList<Item>();
            var client = "import:" + id;
            compiled.exercises().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey(AccessPolicy.LOCK_ORDER))
                    .forEach(entry -> items.add(new Item(AggregateType.EXERCISE, entry.getKey(), null, 0, at, client,
                            1, SCHEMA_VERSION, false, json.valueToTree(entry.getValue()))));
            items.add(new Item(AggregateType.PROGRAM, session.programId(), session.ownerId(), 0, at, client,
                    1, SCHEMA_VERSION, false, json.valueToTree(compiled.program())));
            try {
                var response = sync.createImportedProgram(caller, items);
                long revision = response.results().getLast().revision();
                sessions.committed(id, revision);
                return new Receipt(id, session.ownerId(), session.programId(), revision);
            } catch (SyncPushService.AtomicCreateRejected e) {
                throw ImportFailure.conflict();
            }
        });
    }
}
