package com.sizylift.backend.accountdeletion.repository;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Удаление строки аккаунта; данные пользователя удаляются каскадом по внешним ключам на user_account. */
@Repository
public class AccountDeletionRepository {

    private static final String DELETE_ACCOUNT = "DELETE FROM sizylift.user_account WHERE id = ?";

    private final JdbcTemplate jdbc;

    public AccountDeletionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean delete(UUID userId) {
        return jdbc.update(DELETE_ACCOUNT, userId) == 1;
    }
}
