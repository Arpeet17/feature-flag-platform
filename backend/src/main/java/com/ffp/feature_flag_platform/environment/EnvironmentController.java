package com.ffp.feature_flag_platform.environment;

import com.ffp.feature_flag_platform.environment.EnvironmentDtos.CreateEnvironmentRequest;
import com.ffp.feature_flag_platform.environment.EnvironmentDtos.EnvironmentResponse;
import com.ffp.feature_flag_platform.environment.EnvironmentDtos.EnvironmentWithSdkKey;
import com.ffp.feature_flag_platform.environment.EnvironmentDtos.UpdateEnvironmentRequest;
import com.ffp.feature_flag_platform.evaluation.runtime.EvaluationService;
import com.ffp.feature_flag_platform.sdk.SdkDtos.EvaluateRequest;
import com.ffp.feature_flag_platform.sdk.SdkDtos.EvaluationResponse;
import io.swagger.v3.oas.annotations.Operation;
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

@Tag(name = "Environments")
@RestController
@RequestMapping("/api/v1/projects/{projectKey}/environments")
public class EnvironmentController {

    private final EnvironmentService environments;
    private final EvaluationService evaluation;

    public EnvironmentController(EnvironmentService environments, EvaluationService evaluation) {
        this.environments = environments;
        this.evaluation = evaluation;
    }

    @GetMapping
    @PreAuthorize("hasRole('VIEWER')")
    public List<EnvironmentResponse> list(@PathVariable String projectKey) {
        return environments.list(projectKey);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public EnvironmentWithSdkKey create(@PathVariable String projectKey, @Valid @RequestBody CreateEnvironmentRequest request) {
        return environments.create(projectKey, request);
    }

    @PutMapping("/{environmentKey}")
    @PreAuthorize("hasRole('ADMIN')")
    public EnvironmentResponse update(@PathVariable String projectKey, @PathVariable String environmentKey,
                                      @Valid @RequestBody UpdateEnvironmentRequest request) {
        return environments.update(projectKey, environmentKey, request);
    }

    @Operation(summary = "Issue a new SDK key; the old key stops working immediately on this instance and within 60s everywhere")
    @PostMapping("/{environmentKey}/sdk-key/rotate")
    @PreAuthorize("hasRole('ADMIN')")
    public EnvironmentWithSdkKey rotate(@PathVariable String projectKey, @PathVariable String environmentKey) {
        return environments.rotateSdkKey(projectKey, environmentKey);
    }

    @DeleteMapping("/{environmentKey}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable String projectKey, @PathVariable String environmentKey) {
        environments.delete(projectKey, environmentKey);
    }

    @Operation(summary = "Evaluation debugger: evaluate flags for a context with full reasons, without an SDK key")
    @PostMapping("/{environmentKey}/evaluate")
    @PreAuthorize("hasRole('VIEWER')")
    public EvaluationResponse evaluate(@PathVariable String projectKey, @PathVariable String environmentKey,
                                       @Valid @RequestBody EvaluateRequest request) {
        Environment environment = environments.require(projectKey, environmentKey);
        return EvaluationResponse.from(evaluation.evaluate(environment.getId(), request.context().toDomain(), request.flagKeys()));
    }
}
