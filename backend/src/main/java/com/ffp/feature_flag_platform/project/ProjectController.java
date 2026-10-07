package com.ffp.feature_flag_platform.project;

import com.ffp.feature_flag_platform.project.ProjectDtos.CreateProjectRequest;
import com.ffp.feature_flag_platform.project.ProjectDtos.ProjectCreatedResponse;
import com.ffp.feature_flag_platform.project.ProjectDtos.ProjectResponse;
import com.ffp.feature_flag_platform.project.ProjectDtos.UpdateProjectRequest;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Projects")
@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectService projects;

    public ProjectController(ProjectService projects) {
        this.projects = projects;
    }

    @GetMapping
    @PreAuthorize("hasRole('VIEWER')")
    public List<ProjectResponse> list() {
        return projects.list();
    }

    @GetMapping("/{projectKey}")
    @PreAuthorize("hasRole('VIEWER')")
    public ProjectResponse get(@PathVariable String projectKey) {
        return projects.get(projectKey);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public ProjectCreatedResponse create(@Valid @RequestBody CreateProjectRequest request) {
        return projects.create(request);
    }

    @PutMapping("/{projectKey}")
    @PreAuthorize("hasRole('ADMIN')")
    public ProjectResponse update(@PathVariable String projectKey, @Valid @RequestBody UpdateProjectRequest request) {
        return projects.update(projectKey, request);
    }

    @DeleteMapping("/{projectKey}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable String projectKey) {
        projects.delete(projectKey);
    }
}
