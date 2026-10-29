package com.sizylift.backend.inbox.config;

import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Рабочее место тренера (docs/coach-workspace.md). {@code zone} задаёт «сегодня» для пропущенных тренировок,
 * конца программы и просроченных задач; {@code dailyCron} — когда запускается ежедневная задача «Входящих»
 * (в той же зоне).
 */
@ConfigurationProperties("sizylift.coach-workspace")
public record CoachWorkspaceProperties(ZoneId zone, String dailyCron) {
}
