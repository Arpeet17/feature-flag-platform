package com.ffp.feature_flag_platform.flag;

import com.ffp.feature_flag_platform.audit.AuditEntry.Action;
import com.ffp.feature_flag_platform.audit.AuditEntry.ResourceType;
import com.ffp.feature_flag_platform.audit.AuditService;
import com.ffp.feature_flag_platform.auth.CurrentUser;
import com.ffp.feature_flag_platform.common.ConflictException;
import com.ffp.feature_flag_platform.common.NotFoundException;
import com.ffp.feature_flag_platform.common.PageResponse;
import com.ffp.feature_flag_platform.common.ValidationException;
import com.ffp.feature_flag_platform.environment.Environment;
import com.ffp.feature_flag_platform.environment.EnvironmentRepository;
import com.ffp.feature_flag_platform.evaluation.engine.TargetingValidator;
import com.ffp.feature_flag_platform.evaluation.model.FlagType;
import com.ffp.feature_flag_platform.evaluation.model.Prerequisite;
import com.ffp.feature_flag_platform.evaluation.model.Rule;
import com.ffp.feature_flag_platform.evaluation.model.Target;
import com.ffp.feature_flag_platform.evaluation.model.Targeting;
import com.ffp.feature_flag_platform.evaluation.model.Variation;
import com.ffp.feature_flag_platform.evaluation.runtime.EnvironmentsChangedEvent;
import com.ffp.feature_flag_platform.evaluation.runtime.EvaluationStats;
import com.ffp.feature_flag_platform.flag.FlagDtos.CopyTargetingRequest;
import com.ffp.feature_flag_platform.flag.FlagDtos.CreateFlagRequest;
import com.ffp.feature_flag_platform.flag.FlagDtos.EnvironmentState;
import com.ffp.feature_flag_platform.flag.FlagDtos.FlagConfigResponse;
import com.ffp.feature_flag_platform.flag.FlagDtos.FlagResponse;
import com.ffp.feature_flag_platform.flag.FlagDtos.FlagSummaryResponse;
import com.ffp.feature_flag_platform.flag.FlagDtos.UpdateFlagRequest;
import com.ffp.feature_flag_platform.flag.FlagDtos.UpdateTargetingRequest;
import com.ffp.feature_flag_platform.project.Project;
import com.ffp.feature_flag_platform.project.ProjectRepository;
import com.ffp.feature_flag_platform.segment.Segment;
import com.ffp.feature_flag_platform.segment.SegmentRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.JsonNodeFactory;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class FlagService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_VARIATIONS = 20;

    private final ProjectRepository projects;
    private final EnvironmentRepository environments;
    private final FeatureFlagRepository flags;
    private final FlagConfigRepository configs;
    private final SegmentRepository segments;
    private final EvaluationStats stats;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public FlagService(ProjectRepository projects, EnvironmentRepository environments, FeatureFlagRepository flags,
                       FlagConfigRepository configs, SegmentRepository segments, EvaluationStats stats,
                       AuditService audit, ApplicationEventPublisher events, Clock clock) {
        this.projects = projects;
        this.environments = environments;
        this.flags = flags;
        this.configs = configs;
        this.segments = segments;
        this.stats = stats;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public PageResponse<FlagSummaryResponse> list(String projectKey, String query, String tag, boolean archived, int page, int size) {
        Project project = project(projectKey);
        var pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 200), Sort.by("key"));
        Page<FeatureFlag> result = flags.search(project.getId(), archived, blankToNull(query), blankToNull(tag), pageable);

        // One query for all configs of the page instead of one per flag.
        Map<Long, List<FlagEnvironmentConfig>> configsByFlag = result.isEmpty() ? Map.of()
                : configs.findByFlagIdIn(result.map(FeatureFlag::getId).toList()).stream()
                .collect(Collectors.groupingBy(c -> c.getFlag().getId()));

        var items = result.getContent().stream().map(flag -> {
            List<FlagEnvironmentConfig> flagConfigs = configsByFlag.getOrDefault(flag.getId(), List.of());
            Map<String, EnvironmentState> states = new LinkedHashMap<>();
            flagConfigs.stream()
                    .sorted((a, b) -> Integer.compare(a.getEnvironment().getSortOrder(), b.getEnvironment().getSortOrder()))
                    .forEach(c -> states.put(c.getEnvironment().getKey(), new EnvironmentState(c.isEnabled(), c.getVersion(),
                            TargetingSummary.describe(c.isEnabled(), c.getTargeting(), flag.getVariations()), c.getUpdatedAt())));
            return new FlagSummaryResponse(flag.getKey(), flag.getName(), flag.getDescription(), flag.getType(),
                    flag.getVariations(), Set.copyOf(flag.getTags()), flag.isTemporary(), flag.isArchived(),
                    FlagLifecycle.of(flag, flagConfigs, clock.instant()), states, flag.getCreatedAt(), flag.getUpdatedAt());
        }).toList();
        return PageResponse.of(result, items);
    }

    @Transactional(readOnly = true)
    public FlagResponse get(String projectKey, String flagKey) {
        FeatureFlag flag = flag(project(projectKey), flagKey);
        return toResponse(flag, configs.findByFlagId(flag.getId()));
    }

    @Transactional(readOnly = true)
    public List<String> tags(String projectKey) {
        return flags.findDistinctTags(project(projectKey).getId());
    }

    // ------------------------------------------------------------------ flag lifecycle

    @Transactional
    public FlagResponse create(String projectKey, CreateFlagRequest request) {
        Project project = project(projectKey);
        if (flags.existsByProjectIdAndKey(project.getId(), request.key())) {
            throw new ConflictException("Flag '%s' already exists in project '%s'".formatted(request.key(), projectKey));
        }
        List<Variation> variations = request.variations() == null || request.variations().isEmpty()
                ? defaultVariations(request.type()) : request.variations();
        int on = Objects.requireNonNullElse(request.defaultOnVariation(), 0);
        int off = Objects.requireNonNullElse(request.defaultOffVariation(), variations.size() - 1);
        validateVariations(request.type(), variations, on, off);

        FeatureFlag flag = new FeatureFlag(project, request.key(), request.name(), request.description(), request.type(),
                variations, on, off, newSalt(), Objects.requireNonNullElse(request.temporary(), true), CurrentUser.actor());
        if (request.tags() != null) {
            flag.setTags(request.tags());
        }
        flags.save(flag);

        String actor = CurrentUser.actor();
        List<Environment> envs = environments.findByProjectIdOrderBySortOrderAscIdAsc(project.getId());
        List<FlagEnvironmentConfig> created = configs.saveAll(envs.stream()
                .map(env -> new FlagEnvironmentConfig(flag, env, Targeting.simple(on, off), actor))
                .toList());

        FlagResponse response = toResponse(flag, created);
        audit.record(projectKey, null, ResourceType.FLAG, flag.getKey(), Action.CREATED,
                "Created %s flag '%s'".formatted(flag.getType().name().toLowerCase(), flag.getName()), null, definitionOf(flag));
        publish(envs);
        return response;
    }

    @Transactional
    public FlagResponse update(String projectKey, String flagKey, UpdateFlagRequest request) {
        Project project = project(projectKey);
        FeatureFlag flag = flag(project, flagKey);
        Map<String, Object> before = definitionOf(flag);

        if (request.name() != null) flag.setName(request.name());
        if (request.description() != null) flag.setDescription(request.description());
        if (request.tags() != null) flag.setTags(request.tags());
        if (request.temporary() != null) flag.setTemporary(request.temporary());

        boolean variationsChanged = false;
        if (request.variations() != null || request.defaultOnVariation() != null || request.defaultOffVariation() != null) {
            List<Variation> variations = request.variations() != null ? request.variations() : flag.getVariations();
            int on = Objects.requireNonNullElse(request.defaultOnVariation(), flag.getDefaultOnVariation());
            int off = Objects.requireNonNullElse(request.defaultOffVariation(), flag.getDefaultOffVariation());
            validateVariations(flag.getType(), variations, on, off);
            if (variations.size() < flag.getVariations().size()) {
                ensureVariationsStillReferencedSafely(project, flag, variations.size());
            }
            variationsChanged = !variations.equals(flag.getVariations());
            flag.setVariations(variations);
            flag.setDefaults(on, off);
        }

        List<FlagEnvironmentConfig> flagConfigs = configs.findByFlagId(flag.getId());
        audit.record(projectKey, null, ResourceType.FLAG, flagKey, Action.UPDATED,
                "Updated flag settings" + (variationsChanged ? " and variations" : ""), before, definitionOf(flag));
        if (variationsChanged) {
            publish(flagConfigs.stream().map(FlagEnvironmentConfig::getEnvironment).toList());
        }
        return toResponse(flag, flagConfigs);
    }

    @Transactional
    public FlagResponse archive(String projectKey, String flagKey) {
        Project project = project(projectKey);
        FeatureFlag flag = flag(project, flagKey);
        List<String> dependents = dependentFlags(project, flagKey);
        if (!dependents.isEmpty()) {
            throw new ConflictException("Flag '%s' is a prerequisite of %s; remove those prerequisites first"
                    .formatted(flagKey, dependents));
        }
        flag.setArchived(true);
        List<FlagEnvironmentConfig> flagConfigs = configs.findByFlagId(flag.getId());
        audit.record(projectKey, null, ResourceType.FLAG, flagKey, Action.ARCHIVED, "Archived flag", null, null);
        publish(flagConfigs.stream().map(FlagEnvironmentConfig::getEnvironment).toList());
        return toResponse(flag, flagConfigs);
    }

    @Transactional
    public FlagResponse restore(String projectKey, String flagKey) {
        FeatureFlag flag = flag(project(projectKey), flagKey);
        flag.setArchived(false);
        List<FlagEnvironmentConfig> flagConfigs = configs.findByFlagId(flag.getId());
        audit.record(projectKey, null, ResourceType.FLAG, flagKey, Action.RESTORED, "Restored flag", null, null);
        publish(flagConfigs.stream().map(FlagEnvironmentConfig::getEnvironment).toList());
        return toResponse(flag, flagConfigs);
    }

    /** Hard delete is only allowed for archived flags – a deliberate two-step safety net. */
    @Transactional
    public void delete(String projectKey, String flagKey) {
        FeatureFlag flag = flag(project(projectKey), flagKey);
        if (!flag.isArchived()) {
            throw new ConflictException("Archive flag '%s' before deleting it".formatted(flagKey));
        }
        audit.record(projectKey, null, ResourceType.FLAG, flagKey, Action.DELETED, "Deleted flag", definitionOf(flag), null);
        flags.delete(flag);
    }

    // ------------------------------------------------------------------ per-environment state

    @Transactional(readOnly = true)
    public FlagConfigResponse getConfig(String projectKey, String flagKey, String environmentKey) {
        Project project = project(projectKey);
        FeatureFlag flag = flag(project, flagKey);
        return toConfigResponse(config(flag, environment(project, environmentKey)));
    }

    @Transactional
    public FlagConfigResponse toggle(String projectKey, String flagKey, String environmentKey, boolean enabled, String comment) {
        Project project = project(projectKey);
        FeatureFlag flag = flag(project, flagKey);
        Environment environment = environment(project, environmentKey);
        FlagEnvironmentConfig config = config(flag, environment);
        if (config.isEnabled() == enabled) {
            return toConfigResponse(config);
        }
        config.setEnabled(enabled);
        config.setUpdatedBy(CurrentUser.actor());
        audit.record(projectKey, environmentKey, ResourceType.FLAG_CONFIG, flagKey, Action.TOGGLED,
                withComment("Turned %s in %s".formatted(enabled ? "ON" : "OFF", environment.getName()), comment),
                Map.of("enabled", !enabled), Map.of("enabled", enabled));
        events.publishEvent(EnvironmentsChangedEvent.of(environment.getId()));
        return toConfigResponse(configs.saveAndFlush(config));
    }

    @Transactional
    public FlagConfigResponse updateTargeting(String projectKey, String flagKey, String environmentKey, UpdateTargetingRequest request) {
        Project project = project(projectKey);
        FeatureFlag flag = flag(project, flagKey);
        Environment environment = environment(project, environmentKey);
        FlagEnvironmentConfig config = config(flag, environment);
        checkVersion(config, request.version());
        return applyTargeting(project, flag, environment, config, normalize(request.targeting()),
                request.enabled() == null ? config.isEnabled() : request.enabled(), request.comment(), null);
    }

    @Transactional
    public FlagConfigResponse copyTargeting(String projectKey, String flagKey, String targetEnvironmentKey, CopyTargetingRequest request) {
        Project project = project(projectKey);
        FeatureFlag flag = flag(project, flagKey);
        Environment source = environment(project, request.sourceEnvironment());
        Environment target = environment(project, targetEnvironmentKey);
        if (source.getId().equals(target.getId())) {
            throw new ValidationException("Source and target environment must differ");
        }
        FlagEnvironmentConfig sourceConfig = config(flag, source);
        FlagEnvironmentConfig targetConfig = config(flag, target);
        checkVersion(targetConfig, request.targetVersion());
        boolean enabled = request.includeEnabledState() ? sourceConfig.isEnabled() : targetConfig.isEnabled();
        return applyTargeting(project, flag, target, targetConfig, sourceConfig.getTargeting(), enabled, request.comment(),
                "Copied targeting from %s to %s".formatted(source.getName(), target.getName()));
    }

    private FlagConfigResponse applyTargeting(Project project, FeatureFlag flag, Environment environment,
                                              FlagEnvironmentConfig config, Targeting targeting, boolean enabled,
                                              String comment, String summaryOverride) {
        validateTargeting(project, flag, environment, targeting);
        Map<String, Object> before = configState(config);
        config.setTargeting(targeting);
        config.setEnabled(enabled);
        config.setUpdatedBy(CurrentUser.actor());
        String summary = summaryOverride != null ? summaryOverride
                : "Updated targeting in %s: %s".formatted(environment.getName(),
                TargetingSummary.describe(enabled, targeting, flag.getVariations()));
        audit.record(project.getKey(), environment.getKey(), ResourceType.FLAG_CONFIG, flag.getKey(),
                Action.TARGETING_UPDATED, withComment(summary, comment), before, configState(config));
        events.publishEvent(EnvironmentsChangedEvent.of(environment.getId()));
        // Flush so the response carries the incremented version the client must send next time.
        return toConfigResponse(configs.saveAndFlush(config));
    }

    // ------------------------------------------------------------------ validation

    private void validateTargeting(Project project, FeatureFlag flag, Environment environment, Targeting targeting) {
        Set<String> segmentKeys = segments.findByProjectIdOrderByKeyAsc(project.getId()).stream()
                .map(Segment::getKey).collect(Collectors.toSet());
        List<FlagEnvironmentConfig> envConfigs = configs.findActiveByEnvironmentId(environment.getId());
        Map<String, Integer> variationCounts = new HashMap<>();
        Map<String, List<Prerequisite>> prerequisiteGraph = new HashMap<>();
        for (FlagEnvironmentConfig c : envConfigs) {
            variationCounts.put(c.getFlag().getKey(), c.getFlag().getVariations().size());
            prerequisiteGraph.put(c.getFlag().getKey(), c.getTargeting().prerequisites());
        }
        List<String> errors = new ArrayList<>(TargetingValidator.validate(flag.getKey(), targeting,
                flag.getVariations().size(), segmentKeys, variationCounts::get));
        if (errors.isEmpty()) {
            prerequisiteGraph.put(flag.getKey(), targeting.prerequisites());
            findCycle(flag.getKey(), prerequisiteGraph).ifPresent(cycle ->
                    errors.add("prerequisites: cycle detected " + String.join(" → ", cycle)));
        }
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
    }

    /** DFS over the prerequisite graph of one environment, returning the cycle path if one passes through {@code start}. */
    static java.util.Optional<List<String>> findCycle(String start, Map<String, List<Prerequisite>> graph) {
        java.util.Deque<String> path = new java.util.ArrayDeque<>();
        Set<String> done = new HashSet<>();
        return dfs(start, start, graph, path, done);
    }

    private static java.util.Optional<List<String>> dfs(String node, String start, Map<String, List<Prerequisite>> graph,
                                                        java.util.Deque<String> path, Set<String> done) {
        path.addLast(node);
        for (Prerequisite p : graph.getOrDefault(node, List.of())) {
            if (p.flagKey().equals(start)) {
                List<String> cycle = new ArrayList<>(path);
                cycle.add(start);
                return java.util.Optional.of(cycle);
            }
            if (!done.contains(p.flagKey()) && !path.contains(p.flagKey())) {
                var found = dfs(p.flagKey(), start, graph, path, done);
                if (found.isPresent()) return found;
            }
        }
        path.removeLast();
        done.add(node);
        return java.util.Optional.empty();
    }

    private static void validateVariations(FlagType type, List<Variation> variations, int on, int off) {
        List<String> errors = new ArrayList<>();
        if (variations.size() < 2) {
            errors.add("variations: at least two variations are required");
        }
        if (variations.size() > MAX_VARIATIONS) {
            errors.add("variations: at most %d variations allowed".formatted(MAX_VARIATIONS));
        }
        if (type == FlagType.BOOLEAN && variations.size() != 2) {
            errors.add("variations: boolean flags have exactly two variations");
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < variations.size(); i++) {
            Variation v = variations.get(i);
            if (v == null || !type.accepts(v.value())) {
                errors.add("variations[%d]: value must be a %s".formatted(i, type.name().toLowerCase()));
            } else if (!seen.add(v.value().toString())) {
                errors.add("variations[%d]: duplicate value %s".formatted(i, v.value()));
            }
        }
        if (on < 0 || on >= variations.size()) errors.add("defaultOnVariation is out of range");
        if (off < 0 || off >= variations.size()) errors.add("defaultOffVariation is out of range");
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
    }

    /** Removing variations must not leave any targeting (of this or dependent flags) pointing at them. */
    private void ensureVariationsStillReferencedSafely(Project project, FeatureFlag flag, int newCount) {
        List<String> errors = new ArrayList<>();
        for (FlagEnvironmentConfig c : configs.findByProjectId(project.getId())) {
            String env = c.getEnvironment().getKey();
            if (c.getFlag().getId().equals(flag.getId())) {
                TargetingValidator.validate(flag.getKey(), c.getTargeting(), newCount, Set.of(), k -> Integer.MAX_VALUE)
                        .stream()
                        .filter(e -> e.contains("variation"))
                        .forEach(e -> errors.add("%s: %s".formatted(env, e)));
            } else {
                for (Prerequisite p : c.getTargeting().prerequisites()) {
                    if (p.flagKey().equals(flag.getKey()) && p.variation() >= newCount) {
                        errors.add("%s: flag '%s' requires variation %d".formatted(env, c.getFlag().getKey(), p.variation()));
                    }
                }
            }
        }
        if (!errors.isEmpty()) {
            errors.addFirst("Cannot remove variations that are still referenced by targeting");
            throw new ValidationException(errors);
        }
    }

    private static void checkVersion(FlagEnvironmentConfig config, Long expected) {
        if (expected != null && expected != config.getVersion()) {
            throw new ConflictException("Targeting was changed by %s since you loaded it (version %d, you have %d). Reload and re-apply your changes."
                    .formatted(config.getUpdatedBy(), config.getVersion(), expected));
        }
    }

    /** Assigns stable ids to new rules and drops empty individual targets. */
    private static Targeting normalize(Targeting t) {
        List<Rule> rules = t.rules().stream()
                .map(r -> r.id() == null || r.id().isBlank()
                        ? new Rule(UUID.randomUUID().toString(), r.description(), r.clauses(), r.serve()) : r)
                .toList();
        List<Target> targets = t.targets().stream()
                .map(x -> new Target(x.variation(), x.values().stream().map(String::trim).filter(s -> !s.isEmpty())
                        .collect(Collectors.toSet())))
                .filter(x -> !x.values().isEmpty())
                .toList();
        return new Targeting(t.offVariation(), t.prerequisites(), targets, rules, t.fallthrough());
    }

    // ------------------------------------------------------------------ helpers

    private List<String> dependentFlags(Project project, String flagKey) {
        return configs.findByProjectId(project.getId()).stream()
                .filter(c -> !c.getFlag().isArchived())
                .filter(c -> c.getTargeting().prerequisites().stream().anyMatch(p -> p.flagKey().equals(flagKey)))
                .map(c -> c.getFlag().getKey())
                .distinct().sorted().toList();
    }

    private Project project(String key) {
        return projects.findByKey(key).orElseThrow(() -> new NotFoundException("Project", key));
    }

    private FeatureFlag flag(Project project, String key) {
        return flags.findByProjectIdAndKey(project.getId(), key).orElseThrow(() -> new NotFoundException("Flag", key));
    }

    private Environment environment(Project project, String key) {
        return environments.findByProjectIdAndKey(project.getId(), key).orElseThrow(() -> new NotFoundException("Environment", key));
    }

    private FlagEnvironmentConfig config(FeatureFlag flag, Environment environment) {
        return configs.findByFlagIdAndEnvironmentId(flag.getId(), environment.getId())
                .orElseThrow(() -> new NotFoundException("Flag configuration", flag.getKey() + "/" + environment.getKey()));
    }

    private void publish(List<Environment> envs) {
        events.publishEvent(new EnvironmentsChangedEvent(envs.stream().map(Environment::getId).collect(Collectors.toSet())));
    }

    private FlagResponse toResponse(FeatureFlag flag, List<FlagEnvironmentConfig> flagConfigs) {
        return new FlagResponse(flag.getKey(), flag.getName(), flag.getDescription(), flag.getType(), flag.getVariations(),
                flag.getDefaultOnVariation(), flag.getDefaultOffVariation(), Set.copyOf(flag.getTags()), flag.isTemporary(),
                flag.isArchived(), FlagLifecycle.of(flag, flagConfigs, clock.instant()), flag.getVersion(), flag.getCreatedBy(),
                flag.getCreatedAt(), flag.getUpdatedAt(), flagConfigs.stream().map(this::toConfigResponse).toList(),
                dependentFlags(flag.getProject(), flag.getKey()));
    }

    private FlagConfigResponse toConfigResponse(FlagEnvironmentConfig c) {
        Environment env = c.getEnvironment();
        return new FlagConfigResponse(env.getKey(), env.getName(), c.isEnabled(), c.getTargeting(), c.getVersion(),
                c.getUpdatedBy(), c.getUpdatedAt(), stats.usage(env.getId(), c.getFlag().getKey()));
    }

    private static Map<String, Object> definitionOf(FeatureFlag flag) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", flag.getName());
        m.put("description", flag.getDescription());
        m.put("type", flag.getType());
        m.put("variations", flag.getVariations());
        m.put("defaultOnVariation", flag.getDefaultOnVariation());
        m.put("defaultOffVariation", flag.getDefaultOffVariation());
        m.put("tags", new java.util.TreeSet<>(flag.getTags()));
        m.put("temporary", flag.isTemporary());
        return m;
    }

    private static Map<String, Object> configState(FlagEnvironmentConfig c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", c.isEnabled());
        m.put("targeting", c.getTargeting());
        return m;
    }

    private static List<Variation> defaultVariations(FlagType type) {
        var f = JsonNodeFactory.instance;
        return switch (type) {
            case BOOLEAN -> List.of(new Variation(f.booleanNode(true), "On", null), new Variation(f.booleanNode(false), "Off", null));
            default -> throw new ValidationException("variations are required for %s flags".formatted(type.name().toLowerCase()));
        };
    }

    private static String newSalt() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String withComment(String summary, String comment) {
        return comment == null || comment.isBlank() ? summary : summary + " — \"" + comment.trim() + "\"";
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
