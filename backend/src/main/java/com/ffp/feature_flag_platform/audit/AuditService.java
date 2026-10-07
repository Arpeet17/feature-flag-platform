package com.ffp.feature_flag_platform.audit;

import com.ffp.feature_flag_platform.audit.AuditEntry.Action;
import com.ffp.feature_flag_platform.audit.AuditEntry.ResourceType;
import com.ffp.feature_flag_platform.auth.CurrentUser;
import com.ffp.feature_flag_platform.common.Json;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * Writes audit entries in the <em>same</em> transaction as the change being audited, so a
 * change can never be committed without its audit record (and vice versa).
 */
@Service
public class AuditService {

    private final AuditRepository repository;
    private final Clock clock;

    public AuditService(AuditRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String projectKey, String environmentKey, ResourceType type, String resourceKey, Action action,
                       String summary, Object before, Object after) {
        repository.save(new AuditEntry(projectKey, environmentKey, type, resourceKey, action, CurrentUser.actor(),
                truncate(summary), Json.write(before), Json.write(after), clock.instant()));
    }

    private static String truncate(String s) {
        return s.length() <= 500 ? s : s.substring(0, 497) + "...";
    }
}
