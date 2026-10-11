package com.ffp.feature_flag_platform.segment;

import com.ffp.feature_flag_platform.audit.AuditEntry.Action;
import com.ffp.feature_flag_platform.audit.AuditEntry.ResourceType;
import com.ffp.feature_flag_platform.audit.AuditService;
import com.ffp.feature_flag_platform.common.ConflictException;
import com.ffp.feature_flag_platform.common.NotFoundException;
import com.ffp.feature_flag_platform.common.ValidationException;
import com.ffp.feature_flag_platform.environment.EnvironmentRepository;
import com.ffp.feature_flag_platform.evaluation.engine.TargetingValidator;
import com.ffp.feature_flag_platform.evaluation.model.Operator;
import com.ffp.feature_flag_platform.evaluation.model.SegmentDefinition.SegmentRule;
import com.ffp.feature_flag_platform.evaluation.runtime.EnvironmentsChangedEvent;
import com.ffp.feature_flag_platform.flag.FlagConfigRepository;
import com.ffp.feature_flag_platform.flag.FlagEnvironmentConfig;
import com.ffp.feature_flag_platform.project.Project;
import com.ffp.feature_flag_platform.project.ProjectRepository;
import com.ffp.feature_flag_platform.segment.SegmentDtos.CreateSegmentRequest;
import com.ffp.feature_flag_platform.segment.SegmentDtos.SegmentResponse;
import com.ffp.feature_flag_platform.segment.SegmentDtos.UpdateSegmentRequest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class SegmentService {

    private final ProjectRepository projects;
    private final SegmentRepository segments;
    private final EnvironmentRepository environments;
    private final FlagConfigRepository configs;
    private final AuditService audit;
    private final ApplicationEventPublisher events;

    public SegmentService(ProjectRepository projects, SegmentRepository segments, EnvironmentRepository environments,
                          FlagConfigRepository configs, AuditService audit, ApplicationEventPublisher events) {
        this.projects = projects;
        this.segments = segments;
        this.environments = environments;
        this.configs = configs;
        this.audit = audit;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public List<SegmentResponse> list(String projectKey) {
        Project project = project(projectKey);
        Map<String, List<String>> usage = usage(project);
        return segments.findByProjectIdOrderByKeyAsc(project.getId()).stream()
                .map(s -> toResponse(s, usage.getOrDefault(s.getKey(), List.of()))).toList();
    }

    @Transactional(readOnly = true)
    public SegmentResponse get(String projectKey, String segmentKey) {
        Project project = project(projectKey);
        return toResponse(segment(project, segmentKey), usage(project).getOrDefault(segmentKey, List.of()));
    }

    @Transactional
    public SegmentResponse create(String projectKey, CreateSegmentRequest request) {
        Project project = project(projectKey);
        if (segments.existsByProjectIdAndKey(project.getId(), request.key())) {
            throw new ConflictException("Segment '%s' already exists".formatted(request.key()));
        }
        Segment segment = new Segment(project, request.key());
        apply(segment, request.name(), request.description(), request.included(), request.excluded(), request.rules());
        segments.save(segment);
        audit.record(projectKey, null, ResourceType.SEGMENT, segment.getKey(), Action.CREATED,
                "Created segment '%s'".formatted(segment.getName()), null, stateOf(segment));
        publish(project);
        return toResponse(segment, List.of());
    }

    @Transactional
    public SegmentResponse update(String projectKey, String segmentKey, UpdateSegmentRequest request) {
        Project project = project(projectKey);
        Segment segment = segment(project, segmentKey);
        if (request.version() != null && request.version() != segment.getVersion()) {
            throw new ConflictException("Segment was modified since you loaded it. Reload and try again.");
        }
        Map<String, Object> before = stateOf(segment);
        apply(segment, request.name(), request.description(), request.included(), request.excluded(), request.rules());
        audit.record(projectKey, null, ResourceType.SEGMENT, segmentKey, Action.UPDATED,
                "Updated segment '%s'".formatted(segment.getName()), before, stateOf(segment));
        publish(project);
        segments.saveAndFlush(segment);
        return toResponse(segment, usage(project).getOrDefault(segmentKey, List.of()));
    }

    @Transactional
    public void delete(String projectKey, String segmentKey) {
        Project project = project(projectKey);
        Segment segment = segment(project, segmentKey);
        List<String> usedBy = usage(project).getOrDefault(segmentKey, List.of());
        if (!usedBy.isEmpty()) {
            throw new ConflictException("Segment '%s' is used by flags %s".formatted(segmentKey, usedBy));
        }
        audit.record(projectKey, null, ResourceType.SEGMENT, segmentKey, Action.DELETED,
                "Deleted segment '%s'".formatted(segment.getName()), stateOf(segment), null);
        segments.delete(segment);
        publish(project);
    }

    private static void apply(Segment segment, String name, String description, Set<String> included,
                              Set<String> excluded, List<SegmentRule> rules) {
        List<SegmentRule> safeRules = rules == null ? List.of() : rules;
        List<String> errors = new java.util.ArrayList<>(TargetingValidator.validateSegmentRules(safeRules));
        Set<String> inc = clean(included);
        Set<String> exc = clean(excluded);
        Set<String> both = new HashSet<>(inc);
        both.retainAll(exc);
        if (!both.isEmpty()) {
            errors.add("contexts cannot be both included and excluded: " + both);
        }
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
        segment.setName(name);
        segment.setDescription(description);
        segment.setIncluded(inc);
        segment.setExcluded(exc);
        segment.setRules(safeRules);
    }

    private static Set<String> clean(Set<String> keys) {
        return keys == null ? Set.of() : keys.stream().filter(Objects::nonNull).map(String::trim)
                .filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }

    /** segment key → flags (in any environment) whose rules reference it. */
    private Map<String, List<String>> usage(Project project) {
        Map<String, Set<String>> usage = new LinkedHashMap<>();
        for (FlagEnvironmentConfig c : configs.findByProjectId(project.getId())) {
            c.getTargeting().rules().stream()
                    .flatMap(r -> r.clauses().stream())
                    .filter(cl -> cl.operator() == Operator.SEGMENT_MATCH)
                    .flatMap(cl -> cl.values().stream())
                    .forEach(key -> usage.computeIfAbsent(key, k -> new java.util.TreeSet<>()).add(c.getFlag().getKey()));
        }
        return usage.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> List.copyOf(e.getValue())));
    }

    private void publish(Project project) {
        events.publishEvent(new EnvironmentsChangedEvent(environments.findByProjectIdOrderBySortOrderAscIdAsc(project.getId())
                .stream().map(e -> e.getId()).collect(Collectors.toSet())));
    }

    private Project project(String key) {
        return projects.findByKey(key).orElseThrow(() -> new NotFoundException("Project", key));
    }

    private Segment segment(Project project, String key) {
        return segments.findByProjectIdAndKey(project.getId(), key).orElseThrow(() -> new NotFoundException("Segment", key));
    }

    private static Map<String, Object> stateOf(Segment s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", s.getName());
        m.put("description", s.getDescription());
        m.put("included", new java.util.TreeSet<>(s.getIncluded()));
        m.put("excluded", new java.util.TreeSet<>(s.getExcluded()));
        m.put("rules", s.getRules());
        return m;
    }

    private static SegmentResponse toResponse(Segment s, List<String> usedBy) {
        return new SegmentResponse(s.getKey(), s.getName(), s.getDescription(), new java.util.TreeSet<>(s.getIncluded()),
                new java.util.TreeSet<>(s.getExcluded()), s.getRules(), s.getVersion(), usedBy, s.getCreatedAt(), s.getUpdatedAt());
    }
}
