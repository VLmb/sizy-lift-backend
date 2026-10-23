package com.sizylift.backend.coaching.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Ошибки операций со связями; статус HTTP задан у каждого класса. */
public final class CoachingExceptions {

    private CoachingExceptions() {
    }

    /** Приглашения или связи нет, либо вызывающий в ней не участвует. */
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public static class NotFound extends RuntimeException {
    }

    /** Приглашение использовано, отозвано или истекло. */
    @ResponseStatus(HttpStatus.GONE)
    public static class InviteGone extends RuntimeException {
    }

    /** Операция не разрешена этой стороне: приглашать без режима тренера, менять разрешение за подопечного. */
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public static class Forbidden extends RuntimeException {
    }

    /**
     * Состояние не позволяет: своё приглашение, у подопечного уже есть тренер, связь разорвана, отзывается
     * принятое приглашение.
     */
    @ResponseStatus(HttpStatus.CONFLICT)
    public static class Conflict extends RuntimeException {
    }
}
