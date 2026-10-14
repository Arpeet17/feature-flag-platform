package com.ffp.feature_flag_platform.sdk;

import com.ffp.feature_flag_platform.environment.SdkKeyResolver.SdkEnvironment;
import com.ffp.feature_flag_platform.evaluation.runtime.EvaluationService;
import com.ffp.feature_flag_platform.evaluation.runtime.FlagStreamService;
import com.ffp.feature_flag_platform.sdk.SdkDtos.ContextDto;
import com.ffp.feature_flag_platform.sdk.SdkDtos.EvaluateRequest;
import com.ffp.feature_flag_platform.sdk.SdkDtos.EvaluationResponse;
import com.ffp.feature_flag_platform.sdk.SdkDtos.FlagValue;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import com.ffp.feature_flag_platform.evaluation.runtime.EnvironmentSnapshot;
import com.ffp.feature_flag_platform.sdk.SdkDtos.RulesetResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.WebRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Evaluation API consumed by SDKs. Authenticated by SDK key; the key determines the environment. */
@Tag(name = "SDK")
@SecurityRequirement(name = "sdkKey")
@RestController
@RequestMapping("/api/v1/sdk")
public class SdkController {

    private final EvaluationService evaluation;
    private final FlagStreamService stream;

    public SdkController(EvaluationService evaluation, FlagStreamService stream) {
        this.evaluation = evaluation;
        this.stream = stream;
    }

    @Operation(summary = "Evaluate all (or the requested) flags for a context")
    @PostMapping("/evaluate")
    public EvaluationResponse evaluate(@AuthenticationPrincipal SdkEnvironment environment,
                                       @Valid @RequestBody EvaluateRequest request) {
        return EvaluationResponse.from(evaluation.evaluate(environment.environmentId(), request.context().toDomain(),
                request.flagKeys()));
    }

    @Operation(summary = "Evaluate a single flag for a context")
    @PostMapping("/evaluate/{flagKey}")
    public FlagValue evaluateOne(@AuthenticationPrincipal SdkEnvironment environment, @PathVariable String flagKey,
                                 @Valid @RequestBody ContextDto context) {
        return FlagValue.from(evaluation.evaluateOne(environment.environmentId(), flagKey, context.toDomain()));
    }

    @Operation(summary = "The environment's full ruleset for SDKs that evaluate locally",
            description = "Supports conditional requests: send the last ETag in If-None-Match and receive 304 if nothing changed.")
    @GetMapping("/rules")
    public ResponseEntity<RulesetResponse> rules(@AuthenticationPrincipal SdkEnvironment environment, WebRequest request) {
        EnvironmentSnapshot snapshot = evaluation.snapshot(environment.environmentId());
        String etag = "\"" + snapshot.version() + "\"";
        if (request.checkNotModified(etag)) {
            return null; // 304 Not Modified, headers already set
        }
        return ResponseEntity.ok().eTag(etag).cacheControl(CacheControl.noCache()).body(RulesetResponse.from(snapshot));
    }

    @Operation(summary = "Server-Sent Events stream: 'ready' on connect, 'flags-changed' whenever the environment changes")
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@AuthenticationPrincipal SdkEnvironment environment) {
        return stream.subscribe(environment.environmentId(), evaluation.currentVersion(environment.environmentId()));
    }
}
