package com.ffp.feature_flag_platform.flag;

import com.ffp.feature_flag_platform.common.JsonConverters;
import com.ffp.feature_flag_platform.environment.Environment;
import com.ffp.feature_flag_platform.evaluation.model.Targeting;
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
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * State of one flag in one environment: on/off plus targeting. Versioned for optimistic
 * locking – two people editing production targeting at once must not silently overwrite
 * each other.
 */
@Entity
@Table(name = "flag_configs")
public class FlagEnvironmentConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "flag_id", nullable = false, updatable = false)
    private FeatureFlag flag;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "environment_id", nullable = false, updatable = false)
    private Environment environment;

    @Column(nullable = false)
    private boolean enabled;

    @Convert(converter = JsonConverters.TargetingConverter.class)
    @Column(nullable = false, columnDefinition = "text")
    private Targeting targeting;

    @Version
    private long version;

    @Column(name = "updated_by")
    private String updatedBy;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected FlagEnvironmentConfig() {
    }

    public FlagEnvironmentConfig(FeatureFlag flag, Environment environment, Targeting targeting, String updatedBy) {
        this.flag = flag;
        this.environment = environment;
        this.targeting = targeting;
        this.updatedBy = updatedBy;
    }

    public Long getId() { return id; }
    public FeatureFlag getFlag() { return flag; }
    public Environment getEnvironment() { return environment; }
    public boolean isEnabled() { return enabled; }
    public Targeting getTargeting() { return targeting; }
    public long getVersion() { return version; }
    public String getUpdatedBy() { return updatedBy; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setTargeting(Targeting targeting) { this.targeting = targeting; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
}
