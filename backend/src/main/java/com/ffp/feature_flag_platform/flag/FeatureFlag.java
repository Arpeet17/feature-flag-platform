package com.ffp.feature_flag_platform.flag;

import com.ffp.feature_flag_platform.common.JsonConverters;
import com.ffp.feature_flag_platform.evaluation.model.FlagType;
import com.ffp.feature_flag_platform.evaluation.model.Variation;
import com.ffp.feature_flag_platform.project.Project;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.ElementCollection;
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
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Environment-independent definition of a flag: identity, type and the variations it can
 * serve. Per-environment state lives in {@link FlagEnvironmentConfig}.
 */
@Entity
@Table(name = "feature_flags")
public class FeatureFlag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false, updatable = false)
    private Project project;

    @Column(name = "flag_key", nullable = false, updatable = false)
    private String key;

    @Column(nullable = false)
    private String name;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "flag_type", nullable = false, updatable = false)
    private FlagType type;

    @Convert(converter = JsonConverters.VariationListConverter.class)
    @Column(nullable = false, columnDefinition = "text")
    private List<Variation> variations;

    @ElementCollection
    @CollectionTable(name = "flag_tags", joinColumns = @JoinColumn(name = "flag_id"))
    @Column(name = "tag")
    private Set<String> tags = new TreeSet<>();

    @Column(nullable = false, updatable = false)
    private String salt;

    @Column(name = "default_on_variation", nullable = false)
    private int defaultOnVariation;

    @Column(name = "default_off_variation", nullable = false)
    private int defaultOffVariation;

    /** Temporary flags (releases, experiments) are expected to be removed; permanent ones (kill switches) are not. */
    @Column(nullable = false)
    private boolean temporary;

    @Column(nullable = false)
    private boolean archived;

    @Version
    private long version;

    @Column(name = "created_by", updatable = false)
    private String createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected FeatureFlag() {
    }

    public FeatureFlag(Project project, String key, String name, String description, FlagType type,
                       List<Variation> variations, int defaultOnVariation, int defaultOffVariation,
                       String salt, boolean temporary, String createdBy) {
        this.project = project;
        this.key = key;
        this.name = name;
        this.description = description;
        this.type = type;
        this.variations = List.copyOf(variations);
        this.defaultOnVariation = defaultOnVariation;
        this.defaultOffVariation = defaultOffVariation;
        this.salt = salt;
        this.temporary = temporary;
        this.createdBy = createdBy;
    }

    public Long getId() { return id; }
    public Project getProject() { return project; }
    public String getKey() { return key; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public FlagType getType() { return type; }
    public List<Variation> getVariations() { return variations; }
    public Set<String> getTags() { return tags; }
    public String getSalt() { return salt; }
    public int getDefaultOnVariation() { return defaultOnVariation; }
    public int getDefaultOffVariation() { return defaultOffVariation; }
    public boolean isTemporary() { return temporary; }
    public boolean isArchived() { return archived; }
    public long getVersion() { return version; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setName(String name) { this.name = name; }
    public void setDescription(String description) { this.description = description; }
    public void setVariations(List<Variation> variations) { this.variations = List.copyOf(variations); }
    public void setDefaults(int onVariation, int offVariation) {
        this.defaultOnVariation = onVariation;
        this.defaultOffVariation = offVariation;
    }
    public void setTemporary(boolean temporary) { this.temporary = temporary; }
    public void setArchived(boolean archived) { this.archived = archived; }

    public void setTags(Set<String> tags) {
        this.tags.clear();
        this.tags.addAll(tags);
    }
}
