package com.sizylift.backend.imports.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * singleCallWorkouts — до скольких тренировок модель разбирает программу целиком одним вызовом.
 * Большую программу первый вызов описывает планом, а недели разбираются параллельно частями
 * примерно по workoutsPerChunk тренировок, не больше maxChunks частей и chunkParallelism одновременных
 * запросов на сессию.
 */
@ConfigurationProperties("sizylift.imports")
public record ImportProperties(@DefaultValue("false") boolean enabled, @DefaultValue("true") boolean workerEnabled,
        @DefaultValue("24h") Duration retention, @DefaultValue("10m") Duration lease,
        @DefaultValue("20") int dailyLimit, @DefaultValue("2") int activePerUser,
        @DefaultValue("4") int concurrentRequests, @DefaultValue("3") int clarificationRounds,
        @DefaultValue("2") int attemptsPerRound, @DefaultValue("5242880") int maxFileBytes,
        @DefaultValue("52428800") long maxExpandedBytes, @DefaultValue("10") int maxSheets,
        @DefaultValue("20000") int maxCells, @DefaultValue("200000") int maxSourceChars,
        @DefaultValue("1000000") long maxCostMicros, @DefaultValue("32768") int maxOutputTokens,
        @DefaultValue("300000") long inputMicrosPerMillion, @DefaultValue("1200000") long outputMicrosPerMillion,
        @DefaultValue("8") int singleCallWorkouts, @DefaultValue("12") int workoutsPerChunk,
        @DefaultValue("8") int maxChunks, @DefaultValue("8") int chunkParallelism) {
    public ImportProperties {
        if (retention.isNegative() || retention.isZero() || lease.compareTo(Duration.ofMinutes(1)) < 0
                || dailyLimit < 1 || activePerUser < 1 || concurrentRequests < 1 || concurrentRequests > 16
                || clarificationRounds < 1 || attemptsPerRound < 1 || maxFileBytes < 1
                || maxExpandedBytes < maxFileBytes || maxSheets < 1 || maxCells < 1
                || maxSourceChars < 1 || maxCostMicros < 1 || maxOutputTokens < 1
                || inputMicrosPerMillion < 1 || outputMicrosPerMillion < 1
                || singleCallWorkouts < 0 || workoutsPerChunk < 1 || maxChunks < 1 || maxChunks > 32
                || chunkParallelism < 1 || chunkParallelism > 32) {
            throw new IllegalArgumentException("Некорректные ограничения импорта");
        }
    }
}
