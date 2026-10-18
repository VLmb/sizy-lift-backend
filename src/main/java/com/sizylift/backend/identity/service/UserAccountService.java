package com.sizylift.backend.identity.service;

import java.time.Instant;

import com.sizylift.backend.identity.entity.UserAccount;
import com.sizylift.backend.identity.repository.UserAccountRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserAccountService {

    private final UserAccountRepository accounts;
    private final Counter accountsCreated;

    public UserAccountService(UserAccountRepository accounts, MeterRegistry meters) {
        this.accounts = accounts;
        this.accountsCreated = Counter.builder("sizylift.accounts.created")
                .description("SizyLift accounts created on the first authenticated request")
                .register(meters);
    }

    /**
     * Returns the account of an authenticated user, creating it on the first request (just-in-time provisioning)
     * and keeping e-mail in sync with the identity provider.
     */
    @Transactional
    public UserAccount resolve(ExternalIdentity identity) {
        var account = accounts.findByIssuerAndSubject(identity.issuer(), identity.subject())
                .orElseGet(() -> provision(identity));
        account.updateContact(identity.email(), identity.emailVerified(), Instant.now());
        return account;
    }

    private UserAccount provision(ExternalIdentity identity) {
        if (accounts.insertIfAbsent(identity.issuer(), identity.subject(), identity.email(),
                identity.emailVerified()) == 1) {
            accountsCreated.increment();
        }
        return accounts.findByIssuerAndSubject(identity.issuer(), identity.subject())
                .orElseThrow(() -> new IllegalStateException("User account was not provisioned"));
    }
}
