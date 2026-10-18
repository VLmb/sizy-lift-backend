package com.sizylift.backend.identity.repository;

import java.util.Optional;
import java.util.UUID;

import com.sizylift.backend.identity.entity.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

    Optional<UserAccount> findByIssuerAndSubject(String issuer, String subject);

    /** Race-safe provisioning: concurrent first requests of one user create exactly one row. */
    @Modifying
    @Query(value = """
            INSERT INTO sizylift.user_account (issuer, subject, email, email_verified)
            VALUES (:issuer, :subject, :email, :emailVerified)
            ON CONFLICT (issuer, subject) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("issuer") String issuer,
                       @Param("subject") String subject,
                       @Param("email") String email,
                       @Param("emailVerified") boolean emailVerified);
}
