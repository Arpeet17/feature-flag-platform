package com.ffp.feature_flag_platform.evaluation.runtime;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * After a change commits: drop the stale snapshot, then notify streaming SDKs. Order matters
 * – clients that re-fetch immediately must already see the new data.
 *
 * <p>Scaling note: this propagates within a single instance. With several instances, publish
 * the same event over Postgres LISTEN/NOTIFY or Redis pub/sub and run this handler on each.
 */
@Component
public class ChangePropagator {

    private final SnapshotCache cache;
    private final FlagStreamService stream;

    public ChangePropagator(SnapshotCache cache, FlagStreamService stream) {
        this.cache = cache;
        this.stream = stream;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onChange(EnvironmentsChangedEvent event) {
        for (Long environmentId : event.environmentIds()) {
            cache.invalidate(environmentId);
            if (stream.connectionCount(environmentId) > 0) {
                try {
                    stream.publish(environmentId, cache.get(environmentId).version());
                } catch (RuntimeException e) {
                    // Environment deleted in the same transaction – nothing to publish.
                }
            }
        }
    }
}
