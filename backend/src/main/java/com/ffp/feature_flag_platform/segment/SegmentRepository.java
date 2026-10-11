package com.ffp.feature_flag_platform.segment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SegmentRepository extends JpaRepository<Segment, Long> {

    List<Segment> findByProjectIdOrderByKeyAsc(Long projectId);

    Optional<Segment> findByProjectIdAndKey(Long projectId, String key);

    boolean existsByProjectIdAndKey(Long projectId, String key);
}
