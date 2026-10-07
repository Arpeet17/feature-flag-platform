package com.ffp.feature_flag_platform.flag;

import com.ffp.feature_flag_platform.common.PageResponse;
import com.ffp.feature_flag_platform.flag.FlagDtos.CopyTargetingRequest;
import com.ffp.feature_flag_platform.flag.FlagDtos.CreateFlagRequest;
import com.ffp.feature_flag_platform.flag.FlagDtos.FlagConfigResponse;
import com.ffp.feature_flag_platform.flag.FlagDtos.FlagResponse;
import com.ffp.feature_flag_platform.flag.FlagDtos.FlagSummaryResponse;
import com.ffp.feature_flag_platform.flag.FlagDtos.ToggleRequest;
import com.ffp.feature_flag_platform.flag.FlagDtos.UpdateFlagRequest;
import com.ffp.feature_flag_platform.flag.FlagDtos.UpdateTargetingRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Flags")
@RestController
@RequestMapping("/api/v1/projects/{projectKey}/flags")
public class FlagController {

    private final FlagService flags;

    public FlagController(FlagService flags) {
        this.flags = flags;
    }

    @Operation(summary = "Search flags; each item includes its state in every environment")
    @GetMapping
    @PreAuthorize("hasRole('VIEWER')")
    public PageResponse<FlagSummaryResponse> list(@PathVariable String projectKey,
                                                  @RequestParam(required = false) String q,
                                                  @RequestParam(required = false) String tag,
                                                  @RequestParam(defaultValue = "false") boolean archived,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "50") int size) {
        return flags.list(projectKey, q, tag, archived, page, size);
    }

    @GetMapping("/tags")
    @PreAuthorize("hasRole('VIEWER')")
    public List<String> tags(@PathVariable String projectKey) {
        return flags.tags(projectKey);
    }

    @GetMapping("/{flagKey}")
    @PreAuthorize("hasRole('VIEWER')")
    public FlagResponse get(@PathVariable String projectKey, @PathVariable String flagKey) {
        return flags.get(projectKey, flagKey);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('EDITOR')")
    public FlagResponse create(@PathVariable String projectKey, @Valid @RequestBody CreateFlagRequest request) {
        return flags.create(projectKey, request);
    }

    @PatchMapping("/{flagKey}")
    @PreAuthorize("hasRole('EDITOR')")
    public FlagResponse update(@PathVariable String projectKey, @PathVariable String flagKey,
                               @Valid @RequestBody UpdateFlagRequest request) {
        return flags.update(projectKey, flagKey, request);
    }

    @PostMapping("/{flagKey}/archive")
    @PreAuthorize("hasRole('EDITOR')")
    public FlagResponse archive(@PathVariable String projectKey, @PathVariable String flagKey) {
        return flags.archive(projectKey, flagKey);
    }

    @PostMapping("/{flagKey}/restore")
    @PreAuthorize("hasRole('EDITOR')")
    public FlagResponse restore(@PathVariable String projectKey, @PathVariable String flagKey) {
        return flags.restore(projectKey, flagKey);
    }

    @DeleteMapping("/{flagKey}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable String projectKey, @PathVariable String flagKey) {
        flags.delete(projectKey, flagKey);
    }

    @GetMapping("/{flagKey}/environments/{environmentKey}")
    @PreAuthorize("hasRole('VIEWER')")
    public FlagConfigResponse getConfig(@PathVariable String projectKey, @PathVariable String flagKey,
                                        @PathVariable String environmentKey) {
        return flags.getConfig(projectKey, flagKey, environmentKey);
    }

    @Operation(summary = "Replace targeting (optimistic locking via 'version')")
    @PutMapping("/{flagKey}/environments/{environmentKey}")
    @PreAuthorize("hasRole('EDITOR')")
    public FlagConfigResponse updateTargeting(@PathVariable String projectKey, @PathVariable String flagKey,
                                              @PathVariable String environmentKey,
                                              @Valid @RequestBody UpdateTargetingRequest request) {
        return flags.updateTargeting(projectKey, flagKey, environmentKey, request);
    }

    @Operation(summary = "Kill switch: turn a flag on or off in one environment")
    @PostMapping("/{flagKey}/environments/{environmentKey}/toggle")
    @PreAuthorize("hasRole('EDITOR')")
    public FlagConfigResponse toggle(@PathVariable String projectKey, @PathVariable String flagKey,
                                     @PathVariable String environmentKey, @Valid @RequestBody ToggleRequest request) {
        return flags.toggle(projectKey, flagKey, environmentKey, request.enabled(), request.comment());
    }

    @Operation(summary = "Promote targeting from another environment")
    @PostMapping("/{flagKey}/environments/{environmentKey}/copy")
    @PreAuthorize("hasRole('EDITOR')")
    public FlagConfigResponse copy(@PathVariable String projectKey, @PathVariable String flagKey,
                                   @PathVariable String environmentKey, @Valid @RequestBody CopyTargetingRequest request) {
        return flags.copyTargeting(projectKey, flagKey, environmentKey, request);
    }
}
