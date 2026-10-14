package com.ffp.feature_flag_platform.rollout;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RolloutRepository extends JpaRepository<Rollout, Long> {

    @Query("select r.id from Rollout r where r.status = 'RUNNING' and r.nextCheckAt <= :now order by r.nextCheckAt")
    List<Long> findDueIds(Instant now);

    @Query("select r from Rollout r join fetch r.environment where r.project.id = :projectId order by r.createdAt desc")
    List<Rollout> findByProjectId(Long projectId);

    @Query("select r from Rollout r join fetch r.environment join fetch r.project where r.id = :id")
    Optional<Rollout> findWithEnvironment(Long id);

    boolean existsByEnvironmentIdAndFlagKeyAndStatusIn(Long environmentId, String flagKey, Collection<Rollout.Status> statuses);
}
