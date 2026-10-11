package com.ffp.feature_flag_platform.auth;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Resolves the acting principal for audit purposes. */
public final class CurrentUser {

    public static final String SYSTEM = "system";

    private CurrentUser() {
    }

    public static String actor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null || !auth.isAuthenticated() || auth.getName() == null ? SYSTEM : auth.getName();
    }
}
