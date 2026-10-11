package com.ffp.feature_flag_platform.segment;

import com.ffp.feature_flag_platform.common.JsonConverters;
import com.ffp.feature_flag_platform.evaluation.model.SegmentDefinition;
import com.ffp.feature_flag_platform.evaluation.model.SegmentDefinition.SegmentRule;
import com.ffp.feature_flag_platform.project.Project;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/** Reusable audience (e.g. "beta-testers", "enterprise-customers") referenced from flag rules. */
@Entity
@Table(name = "segments")
public class Segment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false, updatable = false)
    private Project project;

    @Column(name = "segment_key", nullable = false, updatable = false)
    private String key;

    @Column(nullable = false)
    private String name;

    private String description;

    @Convert(converter = JsonConverters.StringSetConverter.class)
    @Column(nullable = false, columnDefinition = "text")
    private Set<String> included;

    @Convert(converter = JsonConverters.StringSetConverter.class)
    @Column(nullable = false, columnDefinition = "text")
    private Set<String> excluded;

    @Convert(converter = JsonConverters.SegmentRuleListConverter.class)
    @Column(nullable = false, columnDefinition = "text")
    private List<SegmentRule> rules;

    @Version
    private long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Segment() {
    }

    public Segment(Project project, String key) {
        this.project = project;
        this.key = key;
    }

    public SegmentDefinition toDefinition() {
        return new SegmentDefinition(key, included, excluded, rules);
    }

    public Long getId() { return id; }
    public Project getProject() { return project; }
    public String getKey() { return key; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public Set<String> getIncluded() { return included; }
    public Set<String> getExcluded() { return excluded; }
    public List<SegmentRule> getRules() { return rules; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setName(String name) { this.name = name; }
    public void setDescription(String description) { this.description = description; }
    public void setIncluded(Set<String> included) { this.included = Set.copyOf(included); }
    public void setExcluded(Set<String> excluded) { this.excluded = Set.copyOf(excluded); }
    public void setRules(List<SegmentRule> rules) { this.rules = List.copyOf(rules); }
}
