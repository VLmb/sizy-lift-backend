package com.sizylift.backend.accountdeletion.service;

import com.sizylift.backend.accountdeletion.repository.AccountDeletionRepository;
import com.sizylift.backend.coaching.service.CoachingService;
import com.sizylift.backend.identity.entity.UserAccount;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Удаление аккаунта со всеми данными (docs/authentication.md, «Удаление аккаунта»). Сначала пользователь
 * удаляется в Keycloak: при его недоступности не удаляется ничего, и запрос можно повторить. Затем в одной
 * транзакции разрываются связи (программы тренера переходят подопечным, тренер получает {@code revoke}) и
 * удаляется строка аккаунта — остальное удаляют каскады. Повтор после сбоя на втором шаге безопасен: Keycloak
 * отвечает 404, это не ошибка.
 */
@Service
public class AccountDeletionService {

    static final String DELETIONS_METER = "sizylift.accounts.deleted";

    private final KeycloakAdminClient keycloak;
    private final CoachingService coaching;
    private final AccountDeletionRepository accounts;
    private final TransactionTemplate transactions;
    private final MeterRegistry meters;
    private final String issuer;

    AccountDeletionService(KeycloakAdminClient keycloak, CoachingService coaching, AccountDeletionRepository accounts,
                           TransactionTemplate transactions, MeterRegistry meters,
                           @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer) {
        this.keycloak = keycloak;
        this.coaching = coaching;
        this.accounts = accounts;
        this.transactions = transactions;
        this.meters = meters;
        this.issuer = issuer;
    }

    public void delete(UserAccount account) {
        // Аккаунт другого issuer (после смены провайдера) не принадлежит этому Keycloak: его sub там ничего не значит.
        if (issuer.equals(account.issuer())) {
            keycloak.deleteUser(account.subject());
        }
        transactions.executeWithoutResult(status -> {
            coaching.endAll(account.id());
            accounts.delete(account.id());
        });
        meters.counter(DELETIONS_METER).increment();
    }
}
