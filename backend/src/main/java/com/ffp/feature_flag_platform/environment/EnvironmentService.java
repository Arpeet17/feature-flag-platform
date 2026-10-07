package com.ffp.feature_flag_platform.environment;

import com.ffp.feature_flag_platform.audit.AuditEntry.Action;
import com.ffp.feature_flag_platform.audit.AuditEntry.ResourceType;
import com.ffp.feature_flag_platform.audit.AuditService;
import com.ffp.feature_flag_platform.auth.CurrentUser;
import com.ffp.feature_flag_platform.common.ConflictException;
import com.ffp.feature_flag_platform.common.NotFoundException;
import com.ffp.feature_flag_platform.environment.EnvironmentDtos.CreateEnvironmentRequest;
import com.ffp.feature_flag_platform.environment.EnvironmentDtos.EnvironmentResponse;
import com.ffp.feature_flag_platform.environment.EnvironmentDtos.EnvironmentWithSdkKey;
import com.ffp.feature_flag_platform.environment.EnvironmentDtos.UpdateEnvironmentRequest;
import com.ffp.feature_flag_platform.evaluation.model.Targeting;
import com.ffp.feature_flag_platform.evaluation.runtime.EnvironmentsChangedEvent;
import com.ffp.feature_flag_platform.flag.FeatureFlag;
import com.ffp.feature_flag_platform.flag.FeatureFlagRepository;
import com.ffp.feature_flag_platform.flag.FlagConfigRepository;
import com.ffp.feature_flag_platform.flag.FlagEnvironmentConfig;
import com.ffp.feature_flag_platform.project.Project;
import com.ffp.feature_flag_platform.project.ProjectRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class EnvironmentService {

    private static final String DEFAULT_COLOR = "#6b7280";

    private final ProjectRepository projects;
    private final EnvironmentRepository environments;
    private final FeatureFlagRepository flags;
    private final FlagConfigRepository configs;
    private final SdkKeyResolver sdkKeyResolver;
    private final AuditService audit;
    private final ApplicationEventPublisher events;

    public EnvironmentService(ProjectRepository projects, EnvironmentRepository environments, FeatureFlagRepository flags,
                              FlagConfigRepository configs, SdkKeyResolver sdkKeyResolver, AuditService audit,
                              ApplicationEventPublisher events) {
        this.projects = projects;
        this.environments = environments;
        this.flags = flags;
        this.configs = configs;
        this.sdkKeyResolver = sdkKeyResolver;
        this.audit = audit;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public List<EnvironmentResponse> list(String projectKey) {
        Project project = project(projectKey);
        return environments.findByProjectIdOrderBySortOrderAscIdAsc(project.getId()).stream()
                .map(EnvironmentResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public Environment require(String projectKey, String environmentKey) {
        Project project = project(projectKey);
        return environments.findByProjectIdAndKey(project.getId(), environmentKey)
                .orElseThrow(() -> new NotFoundException("Environment", environmentKey));
    }

    @Transactional
    public EnvironmentWithSdkKey create(String projectKey, CreateEnvironmentRequest request) {
        Project project = project(projectKey);
        if (environments.existsByProjectIdAndKey(project.getId(), request.key())) {
            throw new ConflictException("Environment '%s' already exists in project '%s'".formatted(request.key(), projectKey));
        }
        int order = (int) environments.countByProjectId(project.getId());
        return createInternal(project, request.key(), request.name(), request.color(), order, null);
    }

    /**
     * Creates the environment and initialises every existing flag in it (off, with the flag's
     * default variations) so that environments always have a complete set of configs.
     *
     * @param rawSdkKey fixed key to use (demo seeding); null generates a random one
     */
    @Transactional
    public EnvironmentWithSdkKey createInternal(Project project, String key, String name, String color, int sortOrder,
                                                String rawSdkKey) {
        Environment environment = new Environment(project, key, name, color == null ? DEFAULT_COLOR : color, sortOrder);
        String sdkKey = rawSdkKey != null ? rawSdkKey : SdkKeys.generate();
        environment.assignSdkKey(SdkKeys.hash(sdkKey), SdkKeys.hint(sdkKey));
        environments.save(environment);

        String actor = CurrentUser.actor();
        List<FeatureFlag> projectFlags = flags.findByProjectId(project.getId());
        configs.saveAll(projectFlags.stream()
                .map(f -> new FlagEnvironmentConfig(f, environment,
                        Targeting.simple(f.getDefaultOnVariation(), f.getDefaultOffVariation()), actor))
                .toList());

        audit.record(project.getKey(), key, ResourceType.ENVIRONMENT, key, Action.CREATED,
                "Created environment '%s'".formatted(name), null, EnvironmentResponse.from(environment));
        return new EnvironmentWithSdkKey(EnvironmentResponse.from(environment), sdkKey);
    }

    @Transactional
    public EnvironmentResponse update(String projectKey, String environmentKey, UpdateEnvironmentRequest request) {
        Environment environment = require(projectKey, environmentKey);
        EnvironmentResponse before = EnvironmentResponse.from(environment);
        environment.setName(request.name());
        if (request.color() != null) {
            environment.setColor(request.color());
        }
        if (request.sortOrder() != null) {
            environment.setSortOrder(request.sortOrder());
        }
        EnvironmentResponse after = EnvironmentResponse.from(environment);
        audit.record(projectKey, environmentKey, ResourceType.ENVIRONMENT, environmentKey, Action.UPDATED,
                "Updated environment '%s'".formatted(environment.getName()), before, after);
        return after;
    }

    @Transactional
    public EnvironmentWithSdkKey rotateSdkKey(String projectKey, String environmentKey) {
        Environment environment = require(projectKey, environmentKey);
        String oldHash = environment.getSdkKeyHash();
        String oldHint = environment.getSdkKeyHint();
        String sdkKey = SdkKeys.generate();
        environment.assignSdkKey(SdkKeys.hash(sdkKey), SdkKeys.hint(sdkKey));
        sdkKeyResolver.evict(oldHash);
        audit.record(projectKey, environmentKey, ResourceType.ENVIRONMENT, environmentKey, Action.SDK_KEY_ROTATED,
                "Rotated SDK key", Map.of("sdkKeyHint", oldHint), Map.of("sdkKeyHint", environment.getSdkKeyHint()));
        return new EnvironmentWithSdkKey(EnvironmentResponse.from(environment), sdkKey);
    }

    @Transactional
    public void delete(String projectKey, String environmentKey) {
        Environment environment = require(projectKey, environmentKey);
        if (environments.countByProjectId(environment.getProject().getId()) <= 1) {
            throw new ConflictException("A project must have at least one environment");
        }
        sdkKeyResolver.evict(environment.getSdkKeyHash());
        audit.record(projectKey, environmentKey, ResourceType.ENVIRONMENT, environmentKey, Action.DELETED,
                "Deleted environment '%s'".formatted(environment.getName()), EnvironmentResponse.from(environment), null);
        environments.delete(environment);
        events.publishEvent(EnvironmentsChangedEvent.of(environment.getId()));
    }

    private Project project(String projectKey) {
        return projects.findByKey(projectKey).orElseThrow(() -> new NotFoundException("Project", projectKey));
    }
}
