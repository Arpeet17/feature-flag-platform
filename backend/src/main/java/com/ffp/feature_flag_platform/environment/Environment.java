package com.ffp.feature_flag_platform.environment;

import com.ffp.feature_flag_platform.project.Project;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/** A deployment stage (development, staging, production, ...) with its own SDK key and flag state. */
@Entity
@Table(name = "environments")
public class Environment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false, updatable = false)
    private Project project;

    @Column(name = "env_key", nullable = false, updatable = false)
    private String key;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String color;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "sdk_key_hash", nullable = false, unique = true)
    private String sdkKeyHash;

    @Column(name = "sdk_key_hint", nullable = false)
    private String sdkKeyHint;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Environment() {
    }

    public Environment(Project project, String key, String name, String color, int sortOrder) {
        this.project = project;
        this.key = key;
        this.name = name;
        this.color = color;
        this.sortOrder = sortOrder;
    }

    public void assignSdkKey(String hash, String hint) {
        this.sdkKeyHash = hash;
        this.sdkKeyHint = hint;
    }

    public Long getId() { return id; }
    public Project getProject() { return project; }
    public String getKey() { return key; }
    public String getName() { return name; }
    public String getColor() { return color; }
    public int getSortOrder() { return sortOrder; }
    public String getSdkKeyHash() { return sdkKeyHash; }
    public String getSdkKeyHint() { return sdkKeyHint; }
    public Instant getCreatedAt() { return createdAt; }

    public void setName(String name) { this.name = name; }
    public void setColor(String color) { this.color = color; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
}
