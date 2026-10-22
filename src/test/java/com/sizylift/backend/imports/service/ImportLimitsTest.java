package com.sizylift.backend.imports.service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Одновременные импорты пользователя и общий семафор на вызовы модели (ADR-0008, раздел 4). */
class ImportLimitsTest {

    final ImportLimits limits = new ImportLimits(ImportTestProperties.defaults(), null, Clock.systemUTC());

    @Test
    void userRunsAtMostTwoImportsAtOnce() {
        var user = UUID.randomUUID();
        var first = limits.userSlot(user);
        var second = limits.userSlot(user);

        assertThatThrownBy(() -> limits.userSlot(user)).isInstanceOf(ImportException.class)
                .extracting(e -> ((ImportException) e).code()).isEqualTo("too_many_concurrent");
        assertThat(limits.userSlot(UUID.randomUUID())).as("другой пользователь не ждёт").isNotNull();

        first.close();
        limits.userSlot(user).close();
        second.close();
    }

    @Test
    void modelCallsShareOneSemaphore() {
        var held = new ArrayList<ImportLimits.Permit>();
        for (int i = 0; i < ImportTestProperties.defaults().modelConcurrency(); i++) {
            held.add(limits.modelSlot());
        }

        assertThatThrownBy(limits::modelSlot).isInstanceOf(ImportException.class)
                .extracting(e -> ((ImportException) e).code()).isEqualTo("import_busy");

        held.removeFirst().close();
        limits.modelSlot().close();
        held.forEach(ImportLimits.Permit::close);
    }
}
