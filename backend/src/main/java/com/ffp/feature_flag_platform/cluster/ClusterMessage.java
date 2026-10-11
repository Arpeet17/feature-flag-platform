package com.ffp.feature_flag_platform.cluster;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Set;

/**
 * Wire format of a change notification between instances.
 *
 * @param origin id of the instance that made the change; it has already applied it locally
 *               and ignores its own message
 */
public record ClusterMessage(String origin, Set<Long> environmentIds, Set<String> revokedSdkKeyHashes) {

    public ClusterMessage {
        environmentIds = environmentIds == null ? Set.of() : Set.copyOf(environmentIds);
        revokedSdkKeyHashes = revokedSdkKeyHashes == null ? Set.of() : Set.copyOf(revokedSdkKeyHashes);
    }

    @JsonIgnore
    public boolean isEmpty() {
        return environmentIds.isEmpty() && revokedSdkKeyHashes.isEmpty();
    }
}
