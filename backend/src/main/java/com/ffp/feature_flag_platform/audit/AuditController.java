package com.ffp.feature_flag_platform.audit;

import com.ffp.feature_flag_platform.common.Json;
import com.ffp.feature_flag_platform.common.PageResponse;
import com.ffp.feature_flag_platform.project.ProjectService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.time.Instant;

@Tag(name = "Audit log")
@RestController
@RequestMapping("/api/v1/projects/{projectKey}/audit")
@PreAuthorize("hasRole('VIEWER')")
public class AuditController {

    private final AuditRepository repository;
    private final ProjectService projects;

    public AuditController(AuditRepository repository, ProjectService projects) {
        this.repository = repository;
        this.projects = projects;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public PageResponse<AuditEntryResponse> list(@PathVariable String projectKey,
                                                 @RequestParam(required = false) AuditEntry.ResourceType resourceType,
                                                 @RequestParam(required = false) String resourceKey,
                                                 @RequestParam(required = false) String environment,
                                                 @RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "25") int size) {
        projects.require(projectKey);
        var pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100), Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        return PageResponse.of(repository.search(projectKey, resourceType, resourceKey, environment, pageable), AuditEntryResponse::from);
    }

    public record AuditEntryResponse(Long id, String projectKey, String environmentKey, AuditEntry.ResourceType resourceType,
                                     String resourceKey, AuditEntry.Action action, String actor, String summary,
                                     JsonNode before, JsonNode after, Instant createdAt) {
        static AuditEntryResponse from(AuditEntry e) {
            return new AuditEntryResponse(e.getId(), e.getProjectKey(), e.getEnvironmentKey(), e.getResourceType(),
                    e.getResourceKey(), e.getAction(), e.getActor(), e.getSummary(),
                    e.getBeforeState() == null ? null : Json.MAPPER.readTree(e.getBeforeState()),
                    e.getAfterState() == null ? null : Json.MAPPER.readTree(e.getAfterState()),
                    e.getCreatedAt());
        }
    }
}
