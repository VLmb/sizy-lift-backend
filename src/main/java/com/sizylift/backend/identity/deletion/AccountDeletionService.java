package com.sizylift.backend.identity.deletion;

import com.sizylift.backend.coaching.service.CoachingService;
import com.sizylift.backend.identity.entity.UserAccount;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Удаление данных и разрыв связей атомарны; удаление в Keycloak повторяется после сбоев. */
@Service
public class AccountDeletionService {
    private static final long RETRY_INTERVAL_MS = 30_000;
    private final AccountDeletionRepository repository;
    private final KeycloakAccountDeletion keycloak;
    private final CoachingService coaching;
    private final TransactionTemplate transactions;
    private final Counter failedAttempts;

    public AccountDeletionService(AccountDeletionRepository repository, KeycloakAccountDeletion keycloak,
                                  CoachingService coaching, TransactionTemplate transactions, MeterRegistry meters) {
        this.repository = repository;
        this.keycloak = keycloak;
        this.coaching = coaching;
        this.transactions = transactions;
        this.failedAttempts = Counter.builder("sizylift.accounts.deletion.failures").register(meters);
        Gauge.builder("sizylift.accounts.deletion.pending", repository, AccountDeletionRepository::pendingCount)
                .register(meters);
    }

    public void delete(UserAccount account) {
        keycloak.requireConfigured();
        keycloak.preflight(account);
        transactions.executeWithoutResult(status -> {
            repository.lockIdentity(account.issuer(), account.subject());
            repository.lockAccount(account.id());
            var overview = coaching.overview(account.id());
            if (overview.coach() != null) {
                coaching.end(account.id(), overview.coach().id());
            }
            // Подопечные обрабатываются в устойчивом порядке.
            overview.athletes().stream().sorted(java.util.Comparator.comparing(link -> link.athleteId().toString()))
                    .forEach(link -> coaching.end(account.id(), link.id()));
            repository.delete(account);
        });
    }

    @Scheduled(fixedDelay = RETRY_INTERVAL_MS)
    public void process() {
        try {
            transactions.executeWithoutResult(status -> repository.next().ifPresent(job -> {
                keycloak.delete(job);
                repository.complete(job.id());
            }));
        } catch (RuntimeException e) {
            failedAttempts.increment();
            // Задача остаётся в БД. Идентификаторы, ответ сервера и секреты в журнал не попадают.
        }
    }
}
