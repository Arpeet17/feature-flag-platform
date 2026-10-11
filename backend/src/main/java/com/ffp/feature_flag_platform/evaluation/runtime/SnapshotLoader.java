package com.ffp.feature_flag_platform.evaluation.runtime;

import com.ffp.feature_flag_platform.common.NotFoundException;
import com.ffp.feature_flag_platform.environment.Environment;
import com.ffp.feature_flag_platform.environment.EnvironmentRepository;
import com.ffp.feature_flag_platform.evaluation.model.FlagDefinition;
import com.ffp.feature_flag_platform.evaluation.model.SegmentDefinition;
import com.ffp.feature_flag_platform.flag.FeatureFlag;
import com.ffp.feature_flag_platform.flag.FlagConfigRepository;
import com.ffp.feature_flag_platform.flag.FlagEnvironmentConfig;
import com.ffp.feature_flag_platform.segment.Segment;
import com.ffp.feature_flag_platform.segment.SegmentRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Builds an {@link EnvironmentSnapshot} with two queries (flag configs + segments). */
@Component
public class SnapshotLoader {

    private static final AtomicLong VERSIONS = new AtomicLong(System.currentTimeMillis());

    private final EnvironmentRepository environments;
    private final FlagConfigRepository configs;
    private final SegmentRepository segments;

    public SnapshotLoader(EnvironmentRepository environments, FlagConfigRepository configs, SegmentRepository segments) {
        this.environments = environments;
        this.configs = configs;
        this.segments = segments;
    }

    /**
     * REQUIRES_NEW: this runs from after-commit callbacks, where joining the finished
     * transaction would not see a consistent connection state.
     */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public EnvironmentSnapshot load(long environmentId) {
        Environment environment = environments.findById(environmentId)
                .orElseThrow(() -> new NotFoundException("Environment", String.valueOf(environmentId)));

        Map<String, FlagDefinition> flags = new HashMap<>();
        for (FlagEnvironmentConfig config : configs.findActiveByEnvironmentId(environmentId)) {
            FeatureFlag flag = config.getFlag();
            flags.put(flag.getKey(), new FlagDefinition(flag.getKey(), flag.getType(), flag.getVariations(),
                    config.isEnabled(), config.getTargeting(), flag.getSalt(), config.getVersion()));
        }

        Map<String, SegmentDefinition> segmentDefinitions = new HashMap<>();
        for (Segment segment : segments.findByProjectIdOrderByKeyAsc(environment.getProject().getId())) {
            segmentDefinitions.put(segment.getKey(), segment.toDefinition());
        }

        return new EnvironmentSnapshot(environmentId, environment.getKey(), VERSIONS.incrementAndGet(), flags, segmentDefinitions);
    }
}
