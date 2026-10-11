package com.ffp.feature_flag_platform.auth;

/**
 * Hierarchical roles: ADMIN > EDITOR > VIEWER (see {@code SecurityConfig#roleHierarchy}).
 * VIEWER reads, EDITOR changes flags/segments, ADMIN manages projects, environments and users.
 */
public enum Role {
    VIEWER,
    EDITOR,
    ADMIN
}
