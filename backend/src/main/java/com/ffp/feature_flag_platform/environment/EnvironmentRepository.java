package com.ffp.feature_flag_platform.environment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface EnvironmentRepository extends JpaRepository<Environment, Long> {

    List<Environment> findByProjectIdOrderBySortOrderAscIdAsc(Long projectId);

    Optional<Environment> findByProjectIdAndKey(Long projectId, String key);

    @Query("select e from Environment e join fetch e.project where e.sdkKeyHash = :hash")
    Optional<Environment> findBySdkKeyHash(String hash);

    boolean existsByProjectIdAndKey(Long projectId, String key);

    long countByProjectId(Long projectId);
}
