package com.ffp.feature_flag_platform.experiment;

import com.ffp.feature_flag_platform.experiment.ExperimentDtos.CreateExperimentRequest;
import com.ffp.feature_flag_platform.experiment.ExperimentDtos.ExperimentResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Experiments")
@RestController
@RequestMapping("/api/v1/projects/{projectKey}/experiments")
public class ExperimentController {

    private final ExperimentService experiments;

    public ExperimentController(ExperimentService experiments) {
        this.experiments = experiments;
    }

    @GetMapping
    @PreAuthorize("hasRole('VIEWER')")
    public List<ExperimentResponse> list(@PathVariable String projectKey) {
        return experiments.list(projectKey);
    }

    @Operation(summary = "Experiment with live statistical results (computed on read)")
    @GetMapping("/{experimentKey}")
    @PreAuthorize("hasRole('VIEWER')")
    public ExperimentResponse get(@PathVariable String projectKey, @PathVariable String experimentKey) {
        return experiments.get(projectKey, experimentKey);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('EDITOR')")
    public ExperimentResponse create(@PathVariable String projectKey, @Valid @RequestBody CreateExperimentRequest request) {
        return experiments.create(projectKey, request);
    }

    @PostMapping("/{experimentKey}/stop")
    @PreAuthorize("hasRole('EDITOR')")
    public ExperimentResponse stop(@PathVariable String projectKey, @PathVariable String experimentKey) {
        return experiments.stop(projectKey, experimentKey);
    }
}
