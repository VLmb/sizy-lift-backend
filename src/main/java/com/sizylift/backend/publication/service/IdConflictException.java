package com.sizylift.backend.publication.service;

/** The aggregate id is already used by a system record or another user. */
public class IdConflictException extends RuntimeException {

    public IdConflictException(String message) {
        super(message);
    }
}
