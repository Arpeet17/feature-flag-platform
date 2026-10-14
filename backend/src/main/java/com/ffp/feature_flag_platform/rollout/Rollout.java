package com.ffp.feature_flag_platform.rollout;

import com.ffp.feature_flag_platform.common.Json;
import com.ffp.feature_flag_platform.environment.Environment;
import com.ffp.feature_flag_platform.project.Project;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import tools.jackson.core.type.TypeReference;

import java.time.Instant;
import java.util.List;

/** A variation being ramped through percentage steps under a guardrail metric. */
@Entity
@Table(name = "rollouts")
public class Rollout {

    public enum Status {
        RUNNING, PAUSED, COMPLETED, ROLLED_BACK, CANCELLED;

        public boolean isActive() {
            return this == RUNNING || this == PAUSED;
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false, updatable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "environment_id", nullable = false, updatable = false)
    private Environment environment;

    @Column(name = "flag_key", nullable = false, updatable = false)
    private String flagKey;

    @Column(name = "treatment_variation", nullable = false, updatable = false)
    private int treatmentVariation;

    @Column(name = "control_variation", nullable = false, updatable = false)
    private int controlVariation;

    @Column(nullable = false, updatable = false)
    private String steps;

    @Column(name = "step_interval_secs", nullable = false, updatable = false)
    private int stepIntervalSeconds;

    @Column(name = "min_users_per_step", nullable = false, updatable = false)
    private int minUsersPerStep;

    @Column(name = "guardrail_event_key", updatable = false)
    private String guardrailEventKey;

    @Column(name = "current_step", nullable = false)
    private int currentStep;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "step_started_at", nullable = false)
    private Instant stepStartedAt;

    @Column(name = "next_check_at", nullable = false)
    private Instant nextCheckAt;

    @Column(name = "expected_config_version", nullable = false)
    private long expectedConfigVersion;

    @Column(name = "last_decision")
    private String lastDecision;

    @Column(name = "created_by", updatable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    private long version;

    protected Rollout() {
    }

    public Rollout(Project project, Environment environment, String flagKey, int treatmentVariation, int controlVariation,
                   List<Integer> steps, int stepIntervalSeconds, int minUsersPerStep, String guardrailEventKey,
                   Instant now, String createdBy) {
        this.project = project;
        this.environment = environment;
        this.flagKey = flagKey;
        this.treatmentVariation = treatmentVariation;
        this.controlVariation = controlVariation;
        this.steps = Json.write(List.copyOf(steps));
        this.stepIntervalSeconds = stepIntervalSeconds;
        this.minUsersPerStep = minUsersPerStep;
        this.guardrailEventKey = guardrailEventKey;
        this.status = Status.RUNNING;
        this.currentStep = 0;
        this.stepStartedAt = now;
        this.nextCheckAt = now;
        this.createdAt = now;
        this.createdBy = createdBy;
    }

    public List<Integer> stepList() {
        return Json.read(steps, new TypeReference<List<Integer>>() { });
    }

    public int currentPercentage() {
        return stepList().get(currentStep);
    }

    public boolean isLastStep() {
        return currentStep == stepList().size() - 1;
    }

    void moveTo(int step, Instant now) {
        this.currentStep = step;
        this.stepStartedAt = now;
    }

    void setStatus(Status status) { this.status = status; }
    void setNextCheckAt(Instant nextCheckAt) { this.nextCheckAt = nextCheckAt; }
    void setExpectedConfigVersion(long v) { this.expectedConfigVersion = v; }
    void setLastDecision(String d) { this.lastDecision = d.length() > 500 ? d.substring(0, 497) + "..." : d; }
    void restartStep(Instant now) { this.stepStartedAt = now; }

    public Long getId() { return id; }
    public Project getProject() { return project; }
    public Environment getEnvironment() { return environment; }
    public String getFlagKey() { return flagKey; }
    public int getTreatmentVariation() { return treatmentVariation; }
    public int getControlVariation() { return controlVariation; }
    public int getStepIntervalSeconds() { return stepIntervalSeconds; }
    public int getMinUsersPerStep() { return minUsersPerStep; }
    public String getGuardrailEventKey() { return guardrailEventKey; }
    public int getCurrentStep() { return currentStep; }
    public Status getStatus() { return status; }
    public Instant getStepStartedAt() { return stepStartedAt; }
    public Instant getNextCheckAt() { return nextCheckAt; }
    public long getExpectedConfigVersion() { return expectedConfigVersion; }
    public String getLastDecision() { return lastDecision; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
