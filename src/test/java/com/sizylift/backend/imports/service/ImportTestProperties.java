package com.sizylift.backend.imports.service;

import java.net.URI;
import java.time.Duration;

import com.sizylift.backend.imports.config.ImportProperties;

/** Настройки импорта для юнит-тестов — как в application.yaml. */
final class ImportTestProperties {

    static final int MAX_FILE_BYTES = 1024 * 1024;
    static final int MAX_CELLS = 2000;

    private ImportTestProperties() {
    }

    static ImportProperties defaults() {
        return new ImportProperties(20000, MAX_FILE_BYTES, 10, MAX_CELLS, 3, 20, 2, 8, Duration.ofSeconds(1),
                Duration.ofHours(24), new ImportProperties.Model(URI.create("https://model.test"), "deepseek-flash",
                "test-key", 16000, Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofMillis(10)),
                new ImportProperties.Sheets(Duration.ofSeconds(1), Duration.ofSeconds(5), 3));
    }
}
