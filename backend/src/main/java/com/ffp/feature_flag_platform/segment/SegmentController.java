package com.ffp.feature_flag_platform.segment;

import com.ffp.feature_flag_platform.segment.SegmentDtos.CreateSegmentRequest;
import com.ffp.feature_flag_platform.segment.SegmentDtos.SegmentResponse;
import com.ffp.feature_flag_platform.segment.SegmentDtos.UpdateSegmentRequest;
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

@Tag(name = "Segments")
@RestController
@RequestMapping("/api/v1/projects/{projectKey}/segments")
public class SegmentController {

    private final SegmentService segments;

    public SegmentController(SegmentService segments) {
        this.segments = segments;
    }

    @GetMapping
    @PreAuthorize("hasRole('VIEWER')")
    public List<SegmentResponse> list(@PathVariable String projectKey) {
        return segments.list(projectKey);
    }

    @GetMapping("/{segmentKey}")
    @PreAuthorize("hasRole('VIEWER')")
    public SegmentResponse get(@PathVariable String projectKey, @PathVariable String segmentKey) {
        return segments.get(projectKey, segmentKey);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('EDITOR')")
    public SegmentResponse create(@PathVariable String projectKey, @Valid @RequestBody CreateSegmentRequest request) {
        return segments.create(projectKey, request);
    }

    @PutMapping("/{segmentKey}")
    @PreAuthorize("hasRole('EDITOR')")
    public SegmentResponse update(@PathVariable String projectKey, @PathVariable String segmentKey,
                                  @Valid @RequestBody UpdateSegmentRequest request) {
        return segments.update(projectKey, segmentKey, request);
    }

    @DeleteMapping("/{segmentKey}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('EDITOR')")
    public void delete(@PathVariable String projectKey, @PathVariable String segmentKey) {
        segments.delete(projectKey, segmentKey);
    }
}
