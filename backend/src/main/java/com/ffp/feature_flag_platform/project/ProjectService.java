package com.ffp.feature_flag_platform.project;

import com.ffp.feature_flag_platform.audit.AuditEntry.Action;
import com.ffp.feature_flag_platform.audit.AuditEntry.ResourceType;
import com.ffp.feature_flag_platform.audit.AuditService;
import com.ffp.feature_flag_platform.common.ConflictException;
import com.ffp.feature_flag_platform.common.NotFoundException;
import com.ffp.feature_flag_platform.environment.EnvironmentDtos.EnvironmentResponse;
import com.ffp.feature_flag_platform.environment.EnvironmentDtos.EnvironmentWithSdkKey;
import com.ffp.feature_flag_platform.environment.EnvironmentRepository;
import com.ffp.feature_flag_platform.environment.EnvironmentService;
import com.ffp.feature_flag_platform.evaluation.runtime.EnvironmentsChangedEvent;
import com.ffp.feature_flag_platform.flag.FeatureFlagRepository;
import com.ffp.feature_flag_platform.project.ProjectDtos.CreateProjectRequest;
import com.ffp.feature_flag_platform.project.ProjectDtos.ProjectCreatedResponse;
import com.ffp.feature_flag_platform.project.ProjectDtos.ProjectResponse;
import com.ffp.feature_flag_platform.project.ProjectDtos.UpdateProjectRequest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;

@Service
public class ProjectService {

    /** Every new project starts with the classic three-stage pipeline. */
    static final List<DefaultEnvironment> DEFAULT_ENVIRONMENTS = List.of(
            new DefaultEnvironment("development", "Development", "#22c55e"),
            new DefaultEnvironment("staging", "Staging", "#f59e0b"),
            new DefaultEnvironment("production", "Production", "#ef4444"));

    private final ProjectRepository projects;
    private final EnvironmentRepository environments;
    private final EnvironmentService environmentService;
    private final FeatureFlagRepository flags;
    private final AuditService audit;
    private final ApplicationEventPublisher events;

    public ProjectService(ProjectRepository projects, EnvironmentRepository environments, EnvironmentService environmentService,
                          FeatureFlagRepository flags, AuditService audit, ApplicationEventPublisher events) {
        this.projects = projects;
        this.environments = environments;
        this.environmentService = environmentService;
        this.flags = flags;
        this.audit = audit;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public Project require(String key) {
        return projects.findByKey(key).orElseThrow(() -> new NotFoundException("Project", key));
    }

    @Transactional(readOnly = true)
    public List<ProjectResponse> list() {
        return projects.findAll(Sort.by("name")).stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public ProjectResponse get(String key) {
        return toResponse(require(key));
    }

    @Transactional
    public ProjectCreatedResponse create(CreateProjectRequest request) {
        return create(request, Map.of());
    }

    /** @param fixedSdkKeys environment key → raw SDK key, used only for demo seeding */
    @Transactional
    public ProjectCreatedResponse create(CreateProjectRequest request, Map<String, String> fixedSdkKeys) {
        if (projects.existsByKey(request.key())) {
            throw new ConflictException("Project '%s' already exists".formatted(request.key()));
        }
        Project project = projects.save(new Project(request.key(), request.name(), request.description()));
        audit.record(project.getKey(), null, ResourceType.PROJECT, project.getKey(), Action.CREATED,
                "Created project '%s'".formatted(project.getName()), null, request);
        List<EnvironmentWithSdkKey> created = new java.util.ArrayList<>();
        for (int i = 0; i < DEFAULT_ENVIRONMENTS.size(); i++) {
            DefaultEnvironment env = DEFAULT_ENVIRONMENTS.get(i);
            created.add(environmentService.createInternal(project, env.key(), env.name(), env.color(), i,
                    fixedSdkKeys.get(env.key())));
        }
        return new ProjectCreatedResponse(toResponse(project), created);
    }

    @Transactional
    public ProjectResponse update(String key, UpdateProjectRequest request) {
        Project project = require(key);
        var before = Map.of("name", project.getName(), "description", String.valueOf(project.getDescription()));
        project.setName(request.name());
        project.setDescription(request.description());
        audit.record(key, null, ResourceType.PROJECT, key, Action.UPDATED, "Updated project details", before, request);
        return toResponse(project);
    }

    @Transactional
    public void delete(String key) {
        Project project = require(key);
        var envIds = new HashSet<Long>();
        environments.findByProjectIdOrderBySortOrderAscIdAsc(project.getId()).forEach(e -> envIds.add(e.getId()));
        audit.record(key, null, ResourceType.PROJECT, key, Action.DELETED,
                "Deleted project '%s'".formatted(project.getName()), Map.of("name", project.getName()), null);
        // Child rows are removed by ON DELETE CASCADE in the schema.
        projects.delete(project);
        events.publishEvent(new EnvironmentsChangedEvent(envIds));
    }

    private ProjectResponse toResponse(Project project) {
        List<EnvironmentResponse> envs = environments.findByProjectIdOrderBySortOrderAscIdAsc(project.getId()).stream()
                .map(EnvironmentResponse::from).toList();
        long flagCount = flags.countByProjectIdAndArchivedFalse(project.getId());
        return new ProjectResponse(project.getId(), project.getKey(), project.getName(), project.getDescription(),
                flagCount, envs, project.getCreatedAt(), project.getUpdatedAt());
    }

    record DefaultEnvironment(String key, String name, String color) {
    }
}
