package com.ffp.feature_flag_platform.audit;

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

/** Immutable record of a change: who did what, when, and the state before and after. */
@Entity
@Immutable
@Table(name = "audit_log")
public class AuditEntry {

    public enum ResourceType { PROJECT, ENVIRONMENT, FLAG, FLAG_CONFIG, SEGMENT, USER }

    public enum Action { CREATED, UPDATED, DELETED, ARCHIVED, RESTORED, TOGGLED, TARGETING_UPDATED, SDK_KEY_ROTATED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_key")
    private String projectKey;

    @Column(name = "environment_key")
    private String environmentKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "resource_type", nullable = false)
    private ResourceType resourceType;

    @Column(name = "resource_key", nullable = false)
    private String resourceKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Action action;

    @Column(nullable = false)
    private String actor;

    @Column(nullable = false)
    private String summary;

    @Column(name = "before_state", columnDefinition = "text")
    private String beforeState;

    @Column(name = "after_state", columnDefinition = "text")
    private String afterState;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AuditEntry() {
    }

    AuditEntry(String projectKey, String environmentKey, ResourceType resourceType, String resourceKey, Action action,
               String actor, String summary, String beforeState, String afterState, Instant createdAt) {
        this.projectKey = projectKey;
        this.environmentKey = environmentKey;
        this.resourceType = resourceType;
        this.resourceKey = resourceKey;
        this.action = action;
        this.actor = actor;
        this.summary = summary;
        this.beforeState = beforeState;
        this.afterState = afterState;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public String getProjectKey() { return projectKey; }
    public String getEnvironmentKey() { return environmentKey; }
    public ResourceType getResourceType() { return resourceType; }
    public String getResourceKey() { return resourceKey; }
    public Action getAction() { return action; }
    public String getActor() { return actor; }
    public String getSummary() { return summary; }
    public String getBeforeState() { return beforeState; }
    public String getAfterState() { return afterState; }
    public Instant getCreatedAt() { return createdAt; }
}
