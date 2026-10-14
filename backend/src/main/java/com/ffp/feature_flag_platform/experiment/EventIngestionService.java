package com.ffp.feature_flag_platform.experiment;

import com.ffp.feature_flag_platform.experiment.EventStore.Exposure;
import com.ffp.feature_flag_platform.experiment.EventStore.MetricEvent;
import com.ffp.feature_flag_platform.experiment.ExperimentDtos.IngestResult;
import com.ffp.feature_flag_platform.experiment.ExperimentDtos.SdkEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Accepts batched SDK events. Client clocks are not trusted blindly: timestamps in the future are
 * clamped to now, and events older than {@link #MAX_AGE} are dropped (late data would silently
 * change results that people may already have acted on).
 */
@Service
public class EventIngestionService {

    static final Duration MAX_AGE = Duration.ofDays(7);
    static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);

    private final EventStore store;
    private final Clock clock;
    private final Counter exposures;
    private final Counter custom;
    private final Counter rejected;

    public EventIngestionService(EventStore store, Clock clock, MeterRegistry meters) {
        this.store = store;
        this.clock = clock;
        this.exposures = Counter.builder("ffp.events.ingested").tag("kind", "exposure").register(meters);
        this.custom = Counter.builder("ffp.events.ingested").tag("kind", "custom").register(meters);
        this.rejected = Counter.builder("ffp.events.ingested").tag("kind", "rejected").register(meters);
    }

    @Transactional
    public IngestResult ingest(long environmentId, List<SdkEvent> events) {
        Instant now = clock.instant();
        List<Exposure> exposureRows = new ArrayList<>();
        List<MetricEvent> metricRows = new ArrayList<>();
        int bad = 0;
        for (SdkEvent e : events) {
            Instant at = e.timestamp() == null ? now : Instant.ofEpochMilli(e.timestamp());
            if (at.isAfter(now.plus(MAX_CLOCK_SKEW))) {
                at = now;
            }
            if (at.isBefore(now.minus(MAX_AGE))) {
                bad++;
                continue;
            }
            if ("exposure".equals(e.kind())) {
                if (e.flagKey() == null || e.variation() == null) {
                    bad++;
                    continue;
                }
                exposureRows.add(new Exposure(environmentId, e.flagKey(), e.contextKey(), e.variation(),
                        Boolean.TRUE.equals(e.inExperiment()), at));
            } else {
                if (e.eventKey() == null || (e.value() != null && !Double.isFinite(e.value()))) {
                    bad++;
                    continue;
                }
                metricRows.add(new MetricEvent(environmentId, e.eventKey(), e.contextKey(), e.value(), at));
            }
        }
        if (!exposureRows.isEmpty()) store.saveExposures(exposureRows);
        if (!metricRows.isEmpty()) store.saveMetricEvents(metricRows);
        exposures.increment(exposureRows.size());
        custom.increment(metricRows.size());
        rejected.increment(bad);
        return new IngestResult(exposureRows.size() + metricRows.size(), bad);
    }
}
