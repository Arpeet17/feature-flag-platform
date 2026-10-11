package com.ffp.feature_flag_platform.cluster;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Single-instance mode: the change is already applied locally and there is nobody else to tell. */
@Component
@ConditionalOnProperty(name = "ffp.cluster.mode", havingValue = "local", matchIfMissing = true)
public class LocalOnlyBroadcaster implements ClusterBroadcaster {

    @Override
    public void broadcast(ClusterMessage message) {
        // nothing to do
    }
}
