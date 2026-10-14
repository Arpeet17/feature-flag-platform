package com.ffp.feature_flag_platform.experiment;

import com.ffp.feature_flag_platform.environment.Environment;
import com.ffp.feature_flag_platform.experiment.stats.ExperimentAnalyzer.MetricType;
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

import java.time.Instant;

/** An A/B/n test: a flag's variations in one environment, judged on one metric against a control. */
@Entity
@Table(name = "experiments")
public class Experiment {

    public enum Status { RUNNING, STOPPED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false, updatable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "environment_id", nullable = false, updatable = false)
    private Environment environment;

    @Column(name = "experiment_key", nullable = false, updatable = false)
    private String key;

    @Column(nullable = false)
    private String name;

    private String hypothesis;

    @Column(name = "flag_key", nullable = false, updatable = false)
    private String flagKey;

    @Column(name = "metric_event_key", nullable = false, updatable = false)
    private String metricEventKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "metric_type", nullable = false, updatable = false)
    private MetricType metricType;

    @Column(name = "higher_is_better", nullable = false, updatable = false)
    private boolean higherIsBetter;

    @Column(name = "control_variation", nullable = false, updatable = false)
    private int controlVariation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "stopped_at")
    private Instant stoppedAt;

    @Column(name = "created_by", updatable = false)
    private String createdBy;

    @Version
    private long version;

    protected Experiment() {
    }

    public Experiment(Project project, Environment environment, String key, String name, String hypothesis, String flagKey,
                      String metricEventKey, MetricType metricType, boolean higherIsBetter, int controlVariation,
                      Instant startedAt, String createdBy) {
        this.project = project;
        this.environment = environment;
        this.key = key;
        this.name = name;
        this.hypothesis = hypothesis;
        this.flagKey = flagKey;
        this.metricEventKey = metricEventKey;
        this.metricType = metricType;
        this.higherIsBetter = higherIsBetter;
        this.controlVariation = controlVariation;
        this.startedAt = startedAt;
        this.createdBy = createdBy;
        this.status = Status.RUNNING;
    }

    public void stop(Instant at) {
        this.status = Status.STOPPED;
        this.stoppedAt = at;
    }

    public Long getId() { return id; }
    public Project getProject() { return project; }
    public Environment getEnvironment() { return environment; }
    public String getKey() { return key; }
    public String getName() { return name; }
    public String getHypothesis() { return hypothesis; }
    public String getFlagKey() { return flagKey; }
    public String getMetricEventKey() { return metricEventKey; }
    public MetricType getMetricType() { return metricType; }
    public boolean isHigherIsBetter() { return higherIsBetter; }
    public int getControlVariation() { return controlVariation; }
    public Status getStatus() { return status; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getStoppedAt() { return stoppedAt; }
    public String getCreatedBy() { return createdBy; }
}
