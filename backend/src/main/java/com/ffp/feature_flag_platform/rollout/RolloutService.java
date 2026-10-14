package com.ffp.feature_flag_platform.rollout;

import com.ffp.feature_flag_platform.audit.AuditEntry.ResourceType;
import com.ffp.feature_flag_platform.audit.AuditService;
import com.ffp.feature_flag_platform.auth.CurrentUser;
import com.ffp.feature_flag_platform.common.ConflictException;
import com.ffp.feature_flag_platform.common.NotFoundException;
import com.ffp.feature_flag_platform.common.ValidationException;
import com.ffp.feature_flag_platform.environment.Environment;
import com.ffp.feature_flag_platform.environment.EnvironmentRepository;
import com.ffp.feature_flag_platform.evaluation.model.Variation;
import com.ffp.feature_flag_platform.flag.FeatureFlag;
import com.ffp.feature_flag_platform.flag.FeatureFlagRepository;
import com.ffp.feature_flag_platform.project.Project;
import com.ffp.feature_flag_platform.project.ProjectRepository;
import com.ffp.feature_flag_platform.rollout.RolloutDtos.CreateRolloutRequest;
import com.ffp.feature_flag_platform.rollout.RolloutDtos.HistoryEntry;
import com.ffp.feature_flag_platform.rollout.RolloutDtos.RolloutResponse;
import com.ffp.feature_flag_platform.rollout.RolloutHistory.Action;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

@Service
public class RolloutService {

    static final int MIN_STEP_INTERVAL_SECONDS = 5;

    private final ProjectRepository projects;
    private final EnvironmentRepository environments;
    private final FeatureFlagRepository flagRepository;
    private final RolloutRepository rollouts;
    private final RolloutHistoryRepository history;
    private final RolloutEngine engine;
    private final AuditService audit;
    private final Clock clock;

    public RolloutService(ProjectRepository projects, EnvironmentRepository environments, FeatureFlagRepository flagRepository,
                          RolloutRepository rollouts, RolloutHistoryRepository history, RolloutEngine engine,
                          AuditService audit, Clock clock) {
        this.projects = projects;
        this.environments = environments;
        this.flagRepository = flagRepository;
        this.rollouts = rollouts;
        this.history = history;
        this.engine = engine;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<RolloutResponse> list(String projectKey) {
        Project project = project(projectKey);
        return rollouts.findByProjectId(project.getId()).stream().map(r -> toResponse(r, false)).toList();
    }

    @Transactional(readOnly = true)
    public RolloutResponse get(String projectKey, long id) {
        return toResponse(rollout(projectKey, id), true);
    }

    /** Starts a rollout: turns the flag on at the first step immediately. */
    @Transactional
    public RolloutResponse start(String projectKey, String flagKey, String environmentKey, CreateRolloutRequest request) {
        Project project = project(projectKey);
        Environment environment = environments.findByProjectIdAndKey(project.getId(), environmentKey)
                .orElseThrow(() -> new NotFoundException("Environment", environmentKey));
        FeatureFlag flag = flagRepository.findByProjectIdAndKey(project.getId(), flagKey)
                .orElseThrow(() -> new NotFoundException("Flag", flagKey));
        validate(flag, request);
        if (rollouts.existsByEnvironmentIdAndFlagKeyAndStatusIn(environment.getId(), flagKey,
                EnumSet.of(Rollout.Status.RUNNING, Rollout.Status.PAUSED))) {
            throw new ConflictException("Flag '%s' already has an active rollout in %s".formatted(flagKey, environment.getName()));
        }
        Instant now = clock.instant();
        Rollout r = rollouts.save(new Rollout(project, environment, flagKey, request.treatmentVariation(),
                request.controlVariation(), request.steps(), request.stepIntervalSeconds(),
                Objects.requireNonNullElse(request.minUsersPerStep(), 0), request.guardrailEventKey(), now, CurrentUser.actor()));
        engine.apply(r, RolloutEngine.split(r), "Guarded rollout started at %d%%.".formatted(r.currentPercentage()));
        r.setNextCheckAt(now.plus(RolloutEngine.checkInterval(r)));
        r.setLastDecision("Started at %d%%.".formatted(r.currentPercentage()));
        engine.record(r, now, Action.STARTED, "Started at %d%% with steps %s every %ds%s.".formatted(r.currentPercentage(),
                request.steps(), request.stepIntervalSeconds(),
                request.guardrailEventKey() == null ? "" : ", guardrail '" + request.guardrailEventKey() + "'"));
        audit.record(projectKey, environmentKey, ResourceType.ROLLOUT, flagKey, com.ffp.feature_flag_platform.audit.AuditEntry.Action.CREATED,
                "Started guarded rollout of %s: steps %s".formatted(label(flag.getVariations(), r.getTreatmentVariation()), request.steps()),
                null, request);
        return toResponse(r, true);
    }

    @Transactional
    public RolloutResponse pause(String projectKey, long id) {
        Rollout r = rollout(projectKey, id);
        requireStatus(r, Rollout.Status.RUNNING);
        r.setStatus(Rollout.Status.PAUSED);
        r.setLastDecision("Paused by " + CurrentUser.actor() + ".");
        engine.record(r, clock.instant(), Action.PAUSED, "Paused by " + CurrentUser.actor() + ".");
        return toResponse(r, true);
    }

    /** Resumes from the current step, re-applying its split on top of the flag's current targeting. */
    @Transactional
    public RolloutResponse resume(String projectKey, long id) {
        Rollout r = rollout(projectKey, id);
        requireStatus(r, Rollout.Status.PAUSED);
        Instant now = clock.instant();
        r.restartStep(now);
        engine.apply(r, RolloutEngine.split(r), "Guarded rollout resumed at %d%%.".formatted(r.currentPercentage()));
        r.setStatus(Rollout.Status.RUNNING);
        r.setNextCheckAt(now.plus(RolloutEngine.checkInterval(r)));
        r.setLastDecision("Resumed at %d%% by %s.".formatted(r.currentPercentage(), CurrentUser.actor()));
        engine.record(r, now, Action.RESUMED, "Resumed by %s; step restarted.".formatted(CurrentUser.actor()));
        return toResponse(r, true);
    }

    /** Stops automation and leaves the flag exactly as it is. */
    @Transactional
    public RolloutResponse cancel(String projectKey, long id) {
        Rollout r = rollout(projectKey, id);
        if (!r.getStatus().isActive()) {
            throw new ConflictException("Rollout is already " + r.getStatus().name().toLowerCase().replace('_', ' '));
        }
        r.setStatus(Rollout.Status.CANCELLED);
        r.setLastDecision("Cancelled by %s; the flag was left at %d%%.".formatted(CurrentUser.actor(), r.currentPercentage()));
        engine.record(r, clock.instant(), Action.CANCELLED, r.getLastDecision());
        return toResponse(r, true);
    }

    private static void validate(FeatureFlag flag, CreateRolloutRequest request) {
        List<String> errors = new ArrayList<>();
        int n = flag.getVariations().size();
        if (request.treatmentVariation() >= n) errors.add("treatmentVariation: flag has %d variations".formatted(n));
        if (request.controlVariation() >= n) errors.add("controlVariation: flag has %d variations".formatted(n));
        if (request.treatmentVariation().equals(request.controlVariation())) errors.add("treatment and control must differ");
        List<Integer> steps = request.steps();
        for (int i = 1; i < steps.size(); i++) {
            if (steps.get(i) <= steps.get(i - 1)) {
                errors.add("steps must be strictly increasing");
                break;
            }
        }
        if (steps.getLast() != 100) errors.add("the last step must be 100");
        if (request.stepIntervalSeconds() < MIN_STEP_INTERVAL_SECONDS) {
            errors.add("stepIntervalSeconds must be at least %d".formatted(MIN_STEP_INTERVAL_SECONDS));
        }
        if (!errors.isEmpty()) throw new ValidationException(errors);
    }

    private static void requireStatus(Rollout r, Rollout.Status expected) {
        if (r.getStatus() != expected) {
            throw new ConflictException("Rollout is %s, not %s".formatted(r.getStatus().name().toLowerCase(), expected.name().toLowerCase()));
        }
    }

    private Project project(String key) {
        return projects.findByKey(key).orElseThrow(() -> new NotFoundException("Project", key));
    }

    private Rollout rollout(String projectKey, long id) {
        Project project = project(projectKey);
        return rollouts.findWithEnvironment(id).filter(r -> r.getProject().getId().equals(project.getId()))
                .orElseThrow(() -> new NotFoundException("Rollout", String.valueOf(id)));
    }

    private RolloutResponse toResponse(Rollout r, boolean detailed) {
        List<Variation> variations = flagRepository.findByProjectIdAndKey(r.getProject().getId(), r.getFlagKey())
                .map(FeatureFlag::getVariations).orElse(List.of());
        List<HistoryEntry> entries = detailed
                ? history.findByRolloutIdOrderByAtAscIdAsc(r.getId()).stream()
                .map(h -> new HistoryEntry(h.getAt(), h.getStep(), h.getPercentage(), h.getAction(), h.getDetail())).toList()
                : null;
        var guardrail = detailed && r.getStatus().isActive() ? engine.guardrail(r, clock.instant()) : null;
        return new RolloutResponse(r.getId(), r.getFlagKey(), r.getEnvironment().getKey(), r.getEnvironment().getName(),
                r.getTreatmentVariation(), label(variations, r.getTreatmentVariation()), r.getControlVariation(),
                label(variations, r.getControlVariation()), r.stepList(), r.getCurrentStep(), r.currentPercentage(), r.getStatus(),
                r.getStepIntervalSeconds(), r.getMinUsersPerStep(), r.getGuardrailEventKey(), r.getStepStartedAt(),
                r.getNextCheckAt(), r.getLastDecision(), r.getCreatedBy(), r.getCreatedAt(), entries,
                guardrail == null ? null : guardrail.analysis(), guardrail == null ? null : guardrail.test());
    }

    private static String label(List<Variation> variations, int index) {
        if (index < 0 || index >= variations.size()) return "#" + index;
        Variation v = variations.get(index);
        return v.name() != null && !v.name().isBlank() ? v.name() : v.value().toString();
    }
}
