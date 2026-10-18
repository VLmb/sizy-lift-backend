package com.sizylift.backend.publication.service;

/** Id агрегата уже занят системной записью или другим пользователем. */
public class IdConflictException extends RuntimeException {

    public IdConflictException(String message) {
        super(message);
    }
}
