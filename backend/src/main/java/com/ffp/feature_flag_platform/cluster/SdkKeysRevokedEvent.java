package com.ffp.feature_flag_platform.cluster;

import java.util.Set;

/**
 * Published when SDK keys stop being valid (rotation, environment deletion). Handled after
 * commit: if the transaction rolls back, the old key must keep working.
 */
public record SdkKeysRevokedEvent(Set<String> keyHashes) {

    public SdkKeysRevokedEvent {
        keyHashes = Set.copyOf(keyHashes);
    }

    public static SdkKeysRevokedEvent of(String keyHash) {
        return new SdkKeysRevokedEvent(Set.of(keyHash));
    }
}
