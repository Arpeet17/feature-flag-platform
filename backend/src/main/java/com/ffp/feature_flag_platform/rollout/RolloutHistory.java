package com.ffp.feature_flag_platform.rollout;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

/** Timeline entry for a rollout. Routine "still waiting" checks are not recorded, only transitions. */
@Entity
@Immutable
@Table(name = "rollout_history")
public class RolloutHistory {

    public enum Action { STARTED, ADVANCED, COMPLETED, ROLLED_BACK, PAUSED, RESUMED, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rollout_id", nullable = false)
    private Long rolloutId;

    @Column(nullable = false)
    private Instant at;

    @Column(nullable = false)
    private int step;

    @Column(nullable = false)
    private int percentage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Action action;

    @Column(nullable = false)
    private String detail;

    protected RolloutHistory() {
    }

    RolloutHistory(Long rolloutId, Instant at, int step, int percentage, Action action, String detail) {
        this.rolloutId = rolloutId;
        this.at = at;
        this.step = step;
        this.percentage = percentage;
        this.action = action;
        this.detail = detail.length() > 1000 ? detail.substring(0, 997) + "..." : detail;
    }

    public Instant getAt() { return at; }
    public int getStep() { return step; }
    public int getPercentage() { return percentage; }
    public Action getAction() { return action; }
    public String getDetail() { return detail; }
}
