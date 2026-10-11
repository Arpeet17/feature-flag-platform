package com.ffp.feature_flag_platform.cluster;

import com.ffp.feature_flag_platform.evaluation.runtime.EnvironmentsChangedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Set;

/**
 * After a change commits, apply it to this instance immediately (so whoever made the change
 * sees it at once, even if the broker is slow or down), then tell the other instances.
 *
 * <p>Handling after commit matters twice over: invalidating earlier would let a concurrent
 * reader rebuild the cache from the old data, and other instances must never be told about a
 * change that is then rolled back.
 */
@Component
public class ChangePropagator {

    private final LocalChangeApplier applier;
    private final ClusterBroadcaster broadcaster;
    private final InstanceId instanceId;

    public ChangePropagator(LocalChangeApplier applier, ClusterBroadcaster broadcaster, InstanceId instanceId) {
        this.applier = applier;
        this.broadcaster = broadcaster;
        this.instanceId = instanceId;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onEnvironmentsChanged(EnvironmentsChangedEvent event) {
        propagate(event.environmentIds(), Set.of());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSdkKeysRevoked(SdkKeysRevokedEvent event) {
        propagate(Set.of(), event.keyHashes());
    }

    private void propagate(Set<Long> environmentIds, Set<String> revokedKeyHashes) {
        applier.apply(environmentIds, revokedKeyHashes);
        broadcaster.broadcast(new ClusterMessage(instanceId.value(), environmentIds, revokedKeyHashes));
    }
}
