package com.ffp.feature_flag_platform.cluster;

import com.ffp.feature_flag_platform.environment.SdkKeyResolver;
import com.ffp.feature_flag_platform.evaluation.runtime.FlagStreamService;
import com.ffp.feature_flag_platform.evaluation.runtime.SnapshotCache;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Applies a change to this instance's in-memory state: revoke SDK keys, drop stale snapshots,
 * then notify this instance's streaming SDKs. Order matters – clients that re-fetch
 * immediately must already see the new data. Used for both local and remote changes, so
 * every instance reacts identically.
 */
@Component
public class LocalChangeApplier {

    private final SnapshotCache cache;
    private final FlagStreamService stream;
    private final SdkKeyResolver sdkKeys;

    public LocalChangeApplier(SnapshotCache cache, FlagStreamService stream, SdkKeyResolver sdkKeys) {
        this.cache = cache;
        this.stream = stream;
        this.sdkKeys = sdkKeys;
    }

    public void apply(Set<Long> environmentIds, Set<String> revokedSdkKeyHashes) {
        revokedSdkKeyHashes.forEach(sdkKeys::evict);
        for (Long environmentId : environmentIds) {
            cache.invalidate(environmentId);
            if (stream.connectionCount(environmentId) > 0) {
                try {
                    stream.publish(environmentId, cache.get(environmentId).version());
                } catch (RuntimeException e) {
                    // Environment deleted – nothing left to publish.
                }
            }
        }
    }
}
