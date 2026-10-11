package com.ffp.feature_flag_platform.common;

/** The request conflicts with current state (duplicate key, stale version, dependent resources). */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
