package com.sizylift.backend.identity.service;

import java.util.Objects;

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

    public UserAccountService(UserAccountRepository accounts, MeterRegistry meters) {
        this.accounts = accounts;
        this.accountsCreated = Counter.builder(ACCOUNTS_CREATED_METER)
                .description("SizyLift accounts created on the first authenticated request")
                .register(meters);
    }

    /**
     * Возвращает аккаунт пользователя из токена: создаёт его при первом запросе
     * и синхронизирует e-mail с Keycloak.
     */
    @Transactional
    public UserAccount resolve(ExternalIdentity identity) {
        var account = accounts.findByIssuerAndSubject(identity.issuer(), identity.subject())
                .orElseGet(() -> provision(identity));
        if (Objects.equals(account.email(), identity.email())
                && account.emailVerified() == identity.emailVerified()) {
            return account;
        }
        return accounts.updateContact(account, identity.email(), identity.emailVerified()).orElse(account);
    }

    private UserAccount provision(ExternalIdentity identity) {
        if (accounts.insertIfAbsent(identity.issuer(), identity.subject(), identity.email(),
                identity.emailVerified())) {
            accountsCreated.increment();
        }
        return accounts.findByIssuerAndSubject(identity.issuer(), identity.subject())
                .orElseThrow(() -> new IllegalStateException("User account was not provisioned"));
    }
}
