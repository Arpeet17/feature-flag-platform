package com.ffp.feature_flag_platform.common;

/** Shared constraints for human-readable resource keys used in URLs and SDK calls. */
public final class Keys {

    public static final String PATTERN = "^[a-z0-9][a-z0-9._-]{0,63}$";
    public static final String MESSAGE = "must be 1-64 chars of lowercase letters, digits, '.', '_' or '-', starting with a letter or digit";

    private Keys() {
    }
}
