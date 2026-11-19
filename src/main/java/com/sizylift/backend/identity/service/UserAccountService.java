package com.sizylift.backend.identity.service;

import java.util.Objects;
import com.sizylift.backend.identity.deletion.AccountDeletionRepository;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import com.sizylift.backend.identity.entity.UserAccount;
import com.sizylift.backend.identity.repository.UserAccountRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserAccountService {

    static final String ACCOUNTS_CREATED_METER = "sizylift.accounts.created";

    private final UserAccountRepository accounts;
    private final Counter accountsCreated;
    private final AccountDeletionRepository retired;

    public UserAccountService(UserAccountRepository accounts, MeterRegistry meters, AccountDeletionRepository retired) {
        this.accounts = accounts;
        this.retired = retired;
        this.accountsCreated = Counter.builder(ACCOUNTS_CREATED_METER)
                .description("Sizy accounts created on the first authenticated request")
                .register(meters);
    }

    /**
     * Возвращает аккаунт пользователя из токена: создаёт его при первом запросе
     * и синхронизирует данные аккаунта с Keycloak.
     */
    @Transactional
    public UserAccount resolve(ExternalIdentity identity) {
        retired.lockIdentity(identity.issuer(), identity.subject());
        if (retired.retired(identity.issuer(), identity.subject())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        var account = accounts.findByIssuerAndSubject(identity.issuer(), identity.subject())
                .orElseGet(() -> provision(identity));
        accounts.touch(account.id());
        var displayName = identity.displayName() != null ? identity.displayName() : account.displayName();
        if (Objects.equals(account.email(), identity.email())
                && account.emailVerified() == identity.emailVerified()
                && Objects.equals(account.displayName(), displayName)) {
            return account;
        }
        return accounts.updateContact(account, identity.email(), identity.emailVerified(), displayName).orElse(account);
    }

    private UserAccount provision(ExternalIdentity identity) {
        if (accounts.insertIfAbsent(identity.issuer(), identity.subject(), identity.email(),
                identity.emailVerified(), identity.displayName())) {
            accountsCreated.increment();
        }
        return accounts.findByIssuerAndSubject(identity.issuer(), identity.subject())
                .orElseThrow(() -> new IllegalStateException("User account was not provisioned"));
    }
}
