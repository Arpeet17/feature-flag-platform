package com.ffp.feature_flag_platform.flag;

import com.ffp.feature_flag_platform.evaluation.model.Targeting;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;

/**
 * Flag hygiene signal. Feature flags are technical debt with an expiry date; surfacing flags
 * that look finished nudges teams to clean them up before they rot.
 */
public enum FlagLifecycle {
    NEW,
    ACTIVE,
    /** Temporary, untouched for a while, and serving one fixed value everywhere – likely safe to remove. */
    POTENTIALLY_STALE,
    PERMANENT,
    ARCHIVED;

    static final Duration NEW_WINDOW = Duration.ofDays(7);
    static final Duration STALE_AFTER = Duration.ofDays(30);

    public static FlagLifecycle of(FeatureFlag flag, Collection<FlagEnvironmentConfig> configs, Instant now) {
        if (flag.isArchived()) {
            return ARCHIVED;
        }
        if (flag.getCreatedAt() != null && flag.getCreatedAt().isAfter(now.minus(NEW_WINDOW))) {
            return NEW;
        }
        if (!flag.isTemporary()) {
            return PERMANENT;
        }
        Instant lastChange = configs.stream().map(FlagEnvironmentConfig::getUpdatedAt)
                .filter(java.util.Objects::nonNull)
                .max(Instant::compareTo).orElse(flag.getUpdatedAt());
        boolean quiet = lastChange == null || lastChange.isBefore(now.minus(STALE_AFTER));
        boolean settled = configs.stream().allMatch(c -> !c.isEnabled() || servesSingleValue(c.getTargeting()));
        return quiet && settled ? POTENTIALLY_STALE : ACTIVE;
    }

    private static boolean servesSingleValue(Targeting t) {
        return t.rules().isEmpty() && t.targets().isEmpty() && t.prerequisites().isEmpty()
                && t.fallthrough() != null && t.fallthrough().variation() != null;
    }
}
