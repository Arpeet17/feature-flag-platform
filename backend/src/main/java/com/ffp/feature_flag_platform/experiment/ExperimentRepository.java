package com.ffp.feature_flag_platform.experiment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ExperimentRepository extends JpaRepository<Experiment, Long> {

    @Query("select e from Experiment e join fetch e.environment where e.project.id = :projectId order by e.startedAt desc")
    List<Experiment> findByProjectId(Long projectId);

    @Query("select e from Experiment e join fetch e.environment where e.project.id = :projectId and e.key = :key")
    Optional<Experiment> findByProjectIdAndKey(Long projectId, String key);

    boolean existsByProjectIdAndKey(Long projectId, String key);
}
