package com.ffp.feature_flag_platform.experiment;

import com.ffp.feature_flag_platform.audit.AuditEntry.Action;
import com.ffp.feature_flag_platform.audit.AuditEntry.ResourceType;
import com.ffp.feature_flag_platform.audit.AuditService;
import com.ffp.feature_flag_platform.auth.CurrentUser;
import com.ffp.feature_flag_platform.common.ConflictException;
import com.ffp.feature_flag_platform.common.NotFoundException;
import com.ffp.feature_flag_platform.common.ValidationException;
import com.ffp.feature_flag_platform.environment.Environment;
import com.ffp.feature_flag_platform.environment.EnvironmentRepository;
import com.ffp.feature_flag_platform.evaluation.model.Rollout;
import com.ffp.feature_flag_platform.evaluation.model.Rule;
import com.ffp.feature_flag_platform.evaluation.model.Targeting;
import com.ffp.feature_flag_platform.evaluation.model.Variation;
import com.ffp.feature_flag_platform.experiment.ExperimentDtos.CreateExperimentRequest;
import com.ffp.feature_flag_platform.experiment.ExperimentDtos.ExperimentResponse;
import com.ffp.feature_flag_platform.experiment.ExperimentDtos.Results;
import com.ffp.feature_flag_platform.experiment.stats.ExperimentAnalyzer;
import com.ffp.feature_flag_platform.experiment.stats.ExperimentAnalyzer.ArmData;
import com.ffp.feature_flag_platform.flag.FeatureFlag;
import com.ffp.feature_flag_platform.flag.FeatureFlagRepository;
import com.ffp.feature_flag_platform.flag.FlagConfigRepository;
import com.ffp.feature_flag_platform.project.Project;
import com.ffp.feature_flag_platform.project.ProjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

@Service
public class ExperimentService {

    static final double ALPHA = 0.05;

    private final ProjectRepository projects;
    private final EnvironmentRepository environments;
    private final FeatureFlagRepository flags;
    private final FlagConfigRepository configs;
    private final ExperimentRepository experiments;
    private final EventStore events;
    private final AuditService audit;
    private final Clock clock;

    public ExperimentService(ProjectRepository projects, EnvironmentRepository environments, FeatureFlagRepository flags,
                             FlagConfigRepository configs, ExperimentRepository experiments, EventStore events,
                             AuditService audit, Clock clock) {
        this.projects = projects;
        this.environments = environments;
        this.flags = flags;
        this.configs = configs;
        this.experiments = experiments;
        this.events = events;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ExperimentResponse> list(String projectKey) {
        Project project = project(projectKey);
        return experiments.findByProjectId(project.getId()).stream().map(e -> toResponse(e, null)).toList();
    }

    @Transactional(readOnly = true)
    public ExperimentResponse get(String projectKey, String experimentKey) {
        Experiment experiment = experiment(project(projectKey), experimentKey);
        return toResponse(experiment, results(experiment));
    }

    @Transactional
    public ExperimentResponse create(String projectKey, CreateExperimentRequest request) {
        Project project = project(projectKey);
        if (experiments.existsByProjectIdAndKey(project.getId(), request.key())) {
            throw new ConflictException("Experiment '%s' already exists".formatted(request.key()));
        }
        Environment environment = environments.findByProjectIdAndKey(project.getId(), request.environment())
                .orElseThrow(() -> new NotFoundException("Environment", request.environment()));
        FeatureFlag flag = flags.findByProjectIdAndKey(project.getId(), request.flagKey())
                .orElseThrow(() -> new NotFoundException("Flag", request.flagKey()));
        int control = Objects.requireNonNullElse(request.controlVariation(), 0);
        if (control >= flag.getVariations().size()) {
            throw new ValidationException("controlVariation: flag '%s' has %d variations".formatted(flag.getKey(), flag.getVariations().size()));
        }
        Experiment experiment = experiments.save(new Experiment(project, environment, request.key(), request.name(),
                request.hypothesis(), flag.getKey(), request.metricEventKey(), request.metricType(),
                Objects.requireNonNullElse(request.higherIsBetter(), true), control, clock.instant(), CurrentUser.actor()));
        audit.record(projectKey, environment.getKey(), ResourceType.EXPERIMENT, experiment.getKey(), Action.CREATED,
                "Started experiment '%s' on flag %s".formatted(experiment.getName(), flag.getKey()), null,
                Map.of("flag", flag.getKey(), "metric", request.metricEventKey(), "control", control));
        return toResponse(experiment, null);
    }

    @Transactional
    public ExperimentResponse stop(String projectKey, String experimentKey) {
        Experiment experiment = experiment(project(projectKey), experimentKey);
        if (experiment.getStatus() == Experiment.Status.STOPPED) {
            return toResponse(experiment, results(experiment));
        }
        experiment.stop(clock.instant());
        audit.record(projectKey, experiment.getEnvironment().getKey(), ResourceType.EXPERIMENT, experiment.getKey(),
                Action.UPDATED, "Stopped experiment '%s'".formatted(experiment.getName()), null, null);
        return toResponse(experiment, results(experiment));
    }

    /** Runs the analysis over the experiment's window. Also used by guarded rollouts. */
    @Transactional(readOnly = true)
    public Results results(Experiment experiment) {
        Instant to = experiment.getStoppedAt() != null ? experiment.getStoppedAt() : clock.instant();
        long envId = experiment.getEnvironment().getId();
        FeatureFlag flag = flags.findByProjectIdAndKey(experiment.getProject().getId(), experiment.getFlagKey()).orElse(null);
        List<Variation> variations = flag == null ? List.of() : flag.getVariations();
        Map<Integer, Integer> expected = flag == null ? Map.of() : expectedWeights(flag, envId);

        Map<Integer, ArmData> byVariation = new TreeMap<>();
        for (ArmData a : events.aggregate(envId, experiment.getFlagKey(), experiment.getMetricEventKey(), experiment.getStartedAt(), to)) {
            byVariation.put(a.variation(), a);
        }
        // Every variation that should receive traffic gets an arm, even with zero users, so a
        // variation that silently receives nothing shows up as a sample ratio mismatch.
        var all = new TreeSet<>(byVariation.keySet());
        expected.forEach((v, w) -> { if (w > 0) all.add(v); });
        all.add(experiment.getControlVariation());
        List<ArmData> arms = new ArrayList<>();
        for (int v : all) {
            ArmData a = byVariation.getOrDefault(v, new ArmData(v, null, 0, 0, 0, 0));
            arms.add(new ArmData(v, label(variations, v), a.users(), a.conversions(), a.sum(), a.sumSquares()));
        }
        var analysis = ExperimentAnalyzer.analyze(arms, experiment.getControlVariation(), experiment.getMetricType(),
                experiment.isHigherIsBetter(), ALPHA, expected);
        long excluded = events.countNonRandomized(envId, experiment.getFlagKey(), experiment.getStartedAt(), to);
        return new Results(experiment.getStartedAt(), to, excluded, analysis);
    }

    /** The configured split: the default rule's rollout, else the first rule that uses one. */
    private Map<Integer, Integer> expectedWeights(FeatureFlag flag, long environmentId) {
        Targeting t = configs.findByFlagIdAndEnvironmentId(flag.getId(), environmentId).map(c -> c.getTargeting()).orElse(null);
        if (t == null) return Map.of();
        Rollout rollout = t.fallthrough() != null ? t.fallthrough().rollout() : null;
        if (rollout == null) {
            rollout = t.rules().stream().map(Rule::serve).filter(s -> s != null && s.rollout() != null)
                    .map(s -> s.rollout()).findFirst().orElse(null);
        }
        if (rollout == null) return Map.of();
        Map<Integer, Integer> weights = new TreeMap<>();
        rollout.variations().forEach(wv -> weights.merge(wv.variation(), wv.weight(), Integer::sum));
        return weights;
    }

    private static String label(List<Variation> variations, int index) {
        if (index < 0 || index >= variations.size()) return "#" + index;
        Variation v = variations.get(index);
        return v.name() != null && !v.name().isBlank() ? v.name() : v.value().toString();
    }

    private Project project(String key) {
        return projects.findByKey(key).orElseThrow(() -> new NotFoundException("Project", key));
    }

    private Experiment experiment(Project project, String key) {
        return experiments.findByProjectIdAndKey(project.getId(), key).orElseThrow(() -> new NotFoundException("Experiment", key));
    }

    private static ExperimentResponse toResponse(Experiment e, Results results) {
        return new ExperimentResponse(e.getKey(), e.getName(), e.getHypothesis(), e.getEnvironment().getKey(),
                e.getEnvironment().getName(), e.getFlagKey(), e.getMetricEventKey(), e.getMetricType(), e.isHigherIsBetter(),
                e.getControlVariation(), e.getStatus(), e.getStartedAt(), e.getStoppedAt(), e.getCreatedBy(), results);
    }
}
