package com.ffp.feature_flag_platform.cluster;

/** Tells the other instances of the platform about a change this instance has already applied. */
public interface ClusterBroadcaster {

    void broadcast(ClusterMessage message);
}
