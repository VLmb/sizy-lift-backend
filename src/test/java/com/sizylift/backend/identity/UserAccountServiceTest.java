package com.sizylift.backend.identity;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import com.sizylift.backend.identity.entity.UserAccount;
import com.sizylift.backend.identity.repository.UserAccountRepository;
import com.sizylift.backend.identity.service.ExternalIdentity;
import com.sizylift.backend.identity.service.UserAccountService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class UserAccountServiceTest {

    private static final String ISSUER = "https://keycloak.test/realms/sizylift";
    private static final String SUBJECT = "subject";
    private static final String EMAIL = "lifter@example.com";

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = EMAIL)
    void unchangedContactsOnlyReadTheAccount(String email) {
        var repository = mock(UserAccountRepository.class);
        var account = account(email, true);
        when(repository.findByIssuerAndSubject(ISSUER, SUBJECT)).thenReturn(Optional.of(account));
        var meters = new SimpleMeterRegistry();
        try {
            var service = new UserAccountService(repository, meters, mock(com.sizylift.backend.identity.deletion.AccountDeletionRepository.class));

            assertThat(service.resolve(new ExternalIdentity(ISSUER, SUBJECT, email, true))).isSameAs(account);

            verify(repository).findByIssuerAndSubject(ISSUER, SUBJECT);
            verifyNoMoreInteractions(repository);
        } finally {
            meters.close();
        }
    }

    @ParameterizedTest
    @MethodSource("changedContacts")
    void changedContactsStillUseTheConditionalUpdate(String email, boolean verified) {
        var repository = mock(UserAccountRepository.class);
        var original = account(EMAIL, true);
        var updated = new UserAccount(original.id(), ISSUER, SUBJECT, email, verified,
                original.createdAt(), original.updatedAt());
        when(repository.findByIssuerAndSubject(ISSUER, SUBJECT)).thenReturn(Optional.of(original));
        when(repository.updateContact(original, email, verified)).thenReturn(Optional.of(updated));
        var meters = new SimpleMeterRegistry();
        try {
            var service = new UserAccountService(repository, meters, mock(com.sizylift.backend.identity.deletion.AccountDeletionRepository.class));

            assertThat(service.resolve(new ExternalIdentity(ISSUER, SUBJECT, email, verified))).isSameAs(updated);

            verify(repository).findByIssuerAndSubject(ISSUER, SUBJECT);
            verify(repository).updateContact(original, email, verified);
            verifyNoMoreInteractions(repository);
        } finally {
            meters.close();
        }
    }

    private static Stream<Arguments> changedContacts() {
        return Stream.of(Arguments.of("new@example.com", true), Arguments.of(null, true),
                Arguments.of(EMAIL, false));
    }

    private static UserAccount account(String email, boolean verified) {
        return new UserAccount(UUID.randomUUID(), ISSUER, SUBJECT, email, verified,
                Instant.EPOCH, Instant.EPOCH);
    }
}
