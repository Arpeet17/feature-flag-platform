package com.ffp.feature_flag_platform.rollout;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Claims a rollout row for processing inside the current transaction. On PostgreSQL this is
 * {@code FOR UPDATE SKIP LOCKED}: when several instances run the scheduler, each due rollout is
 * processed by exactly one of them and the others move on instead of queueing behind the lock.
 * The entity's {@code @Version} remains the correctness backstop either way.
 */
@Component
public class RolloutLock {

    private final JdbcTemplate jdbc;
    private final String sql;

    public RolloutLock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        String product = jdbc.execute((ConnectionCallback<String>) c -> c.getMetaData().getDatabaseProductName());
        this.sql = "PostgreSQL".equalsIgnoreCase(product)
                ? "SELECT id FROM rollouts WHERE id = ? FOR UPDATE SKIP LOCKED"
                : "SELECT id FROM rollouts WHERE id = ? FOR UPDATE";
    }

    /** @return true if this transaction now holds the row lock; false if another instance has it */
    public boolean tryClaim(long rolloutId) {
        List<Long> ids = jdbc.queryForList(sql, Long.class, rolloutId);
        return !ids.isEmpty();
    }
}
