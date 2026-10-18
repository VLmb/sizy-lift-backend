package com.sizylift.backend.identity.service;

import java.time.Instant;

import com.sizylift.backend.identity.entity.UserAccount;
import com.sizylift.backend.identity.repository.UserAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserAccountService {

    private final UserAccountRepository accounts;

    public UserAccountService(UserAccountRepository accounts) {
        this.accounts = accounts;
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
        accounts.insertIfAbsent(identity.issuer(), identity.subject(), identity.email(), identity.emailVerified());
        return accounts.findByIssuerAndSubject(identity.issuer(), identity.subject())
                .orElseThrow(() -> new IllegalStateException("User account was not provisioned"));
    }
}
