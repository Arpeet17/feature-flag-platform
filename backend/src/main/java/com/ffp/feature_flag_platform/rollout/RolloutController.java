package com.ffp.feature_flag_platform.rollout;

import com.ffp.feature_flag_platform.rollout.RolloutDtos.CreateRolloutRequest;
import com.ffp.feature_flag_platform.rollout.RolloutDtos.RolloutResponse;
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

@Tag(name = "Guarded rollouts")
@RestController
@RequestMapping("/api/v1/projects/{projectKey}")
public class RolloutController {

    private final RolloutService rollouts;

    public RolloutController(RolloutService rollouts) {
        this.rollouts = rollouts;
    }

    @GetMapping("/rollouts")
    @PreAuthorize("hasRole('VIEWER')")
    public List<RolloutResponse> list(@PathVariable String projectKey) {
        return rollouts.list(projectKey);
    }

    @Operation(summary = "Rollout with its timeline and the live guardrail analysis for the current step")
    @GetMapping("/rollouts/{id}")
    @PreAuthorize("hasRole('VIEWER')")
    public RolloutResponse get(@PathVariable String projectKey, @PathVariable long id) {
        return rollouts.get(projectKey, id);
    }

    @Operation(summary = "Start a guarded progressive rollout of a variation (turns the flag on at the first step)")
    @PostMapping("/flags/{flagKey}/environments/{environmentKey}/rollouts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('EDITOR')")
    public RolloutResponse start(@PathVariable String projectKey, @PathVariable String flagKey, @PathVariable String environmentKey,
                                 @Valid @RequestBody CreateRolloutRequest request) {
        return rollouts.start(projectKey, flagKey, environmentKey, request);
    }

    @PostMapping("/rollouts/{id}/pause")
    @PreAuthorize("hasRole('EDITOR')")
    public RolloutResponse pause(@PathVariable String projectKey, @PathVariable long id) {
        return rollouts.pause(projectKey, id);
    }

    @PostMapping("/rollouts/{id}/resume")
    @PreAuthorize("hasRole('EDITOR')")
    public RolloutResponse resume(@PathVariable String projectKey, @PathVariable long id) {
        return rollouts.resume(projectKey, id);
    }

    @PostMapping("/rollouts/{id}/cancel")
    @PreAuthorize("hasRole('EDITOR')")
    public RolloutResponse cancel(@PathVariable String projectKey, @PathVariable long id) {
        return rollouts.cancel(projectKey, id);
    }
}
