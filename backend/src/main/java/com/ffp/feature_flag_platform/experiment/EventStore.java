package com.ffp.feature_flag_platform.experiment;

import com.ffp.feature_flag_platform.experiment.stats.ExperimentAnalyzer.ArmData;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Plain JDBC for the high-volume, append-mostly event tables: batched inserts and one
 * aggregate query per analysis. JPA would add per-row overhead and nothing of value here.
 */
@Repository
public class EventStore {

    public record Exposure(long environmentId, String flagKey, String contextKey, int variation, boolean randomized, Instant at) {
    }

    public record MetricEvent(long environmentId, String eventKey, String contextKey, Double value, Instant at) {
    }

    private final JdbcTemplate jdbc;
    private final String insertExposureSql;

    public EventStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        String product = jdbc.execute((ConnectionCallback<String>) c -> c.getMetaData().getDatabaseProductName());
        // First exposure wins. PostgreSQL's ON CONFLICT is race-free; H2 (dev/tests) uses an
        // equivalent NOT EXISTS guard.
        this.insertExposureSql = "PostgreSQL".equalsIgnoreCase(product)
                ? """
                  INSERT INTO exposures (environment_id, flag_key, context_key, variation, randomized, exposed_at)
                  VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING"""
                : """
                  INSERT INTO exposures (environment_id, flag_key, context_key, variation, randomized, exposed_at)
                  SELECT ?, ?, ?, ?, ?, ? FROM (VALUES (1)) t(x)
                  WHERE NOT EXISTS (SELECT 1 FROM exposures WHERE environment_id = ? AND flag_key = ? AND context_key = ?)""";
    }

    public void saveExposures(List<Exposure> exposures) {
        boolean guard = insertExposureSql.contains("NOT EXISTS");
        jdbc.batchUpdate(insertExposureSql, exposures, 500, (ps, e) -> {
            ps.setLong(1, e.environmentId());
            ps.setString(2, e.flagKey());
            ps.setString(3, e.contextKey());
            ps.setInt(4, e.variation());
            ps.setBoolean(5, e.randomized());
            ps.setTimestamp(6, Timestamp.from(e.at()));
            if (guard) {
                ps.setLong(7, e.environmentId());
                ps.setString(8, e.flagKey());
                ps.setString(9, e.contextKey());
            }
        });
    }

    public void saveMetricEvents(List<MetricEvent> events) {
        jdbc.batchUpdate("""
                INSERT INTO metric_events (environment_id, event_key, context_key, numeric_value, occurred_at)
                VALUES (?, ?, ?, ?, ?)""", events, 500, (ps, e) -> {
            ps.setLong(1, e.environmentId());
            ps.setString(2, e.eventKey());
            ps.setString(3, e.contextKey());
            if (e.value() == null) ps.setNull(4, java.sql.Types.DOUBLE);
            else ps.setDouble(4, e.value());
            ps.setTimestamp(5, Timestamp.from(e.at()));
        });
    }

    /**
     * Per-variation aggregates for randomized users exposed inside the window. A user counts
     * as converted if they sent the metric event at or after their first exposure; their value
     * is the sum of event values in that period (0 if none), e.g. revenue per user.
     */
    public List<ArmData> aggregate(long environmentId, String flagKey, String eventKey, Instant from, Instant to) {
        return jdbc.query("""
                SELECT pu.variation, COUNT(*) AS users,
                       SUM(CASE WHEN pu.events > 0 THEN 1 ELSE 0 END) AS converted,
                       SUM(pu.total) AS value_sum, SUM(pu.total * pu.total) AS value_sq
                FROM (
                    SELECT e.variation, e.context_key, COUNT(m.id) AS events, COALESCE(SUM(m.numeric_value), 0) AS total
                    FROM exposures e
                    LEFT JOIN metric_events m
                           ON m.environment_id = e.environment_id AND m.context_key = e.context_key
                          AND m.event_key = ? AND m.occurred_at >= e.exposed_at AND m.occurred_at <= ?
                    WHERE e.environment_id = ? AND e.flag_key = ? AND e.randomized = TRUE
                      AND e.exposed_at >= ? AND e.exposed_at <= ?
                    GROUP BY e.variation, e.context_key
                ) pu
                GROUP BY pu.variation
                ORDER BY pu.variation""",
                (rs, i) -> new ArmData(rs.getInt("variation"), null, rs.getLong("users"), rs.getLong("converted"),
                        rs.getDouble("value_sum"), rs.getDouble("value_sq")),
                eventKey, Timestamp.from(to), environmentId, flagKey, Timestamp.from(from), Timestamp.from(to));
    }

    /** Randomized users first exposed to {@code variation} inside the window. */
    public long countExposures(long environmentId, String flagKey, int variation, Instant from, Instant to) {
        Long n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM exposures
                WHERE environment_id = ? AND flag_key = ? AND variation = ? AND randomized = TRUE
                  AND exposed_at >= ? AND exposed_at <= ?""",
                Long.class, environmentId, flagKey, variation, Timestamp.from(from), Timestamp.from(to));
        return n == null ? 0 : n;
    }

    /** Exposures in the window that were NOT randomized (served by targeting rules) and are excluded. */
    public long countNonRandomized(long environmentId, String flagKey, Instant from, Instant to) {
        Long n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM exposures
                WHERE environment_id = ? AND flag_key = ? AND randomized = FALSE AND exposed_at >= ? AND exposed_at <= ?""",
                Long.class, environmentId, flagKey, Timestamp.from(from), Timestamp.from(to));
        return n == null ? 0 : n;
    }
}
