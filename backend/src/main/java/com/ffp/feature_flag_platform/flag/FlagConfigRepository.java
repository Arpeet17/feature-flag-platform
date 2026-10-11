package com.ffp.feature_flag_platform.flag;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface FlagConfigRepository extends JpaRepository<FlagEnvironmentConfig, Long> {

    @Query("select c from FlagEnvironmentConfig c join fetch c.environment where c.flag.id = :flagId order by c.environment.sortOrder, c.environment.id")
    List<FlagEnvironmentConfig> findByFlagId(Long flagId);

    /** Batch load for list views – avoids an N+1 query per flag. */
    @Query("select c from FlagEnvironmentConfig c join fetch c.environment where c.flag.id in :flagIds")
    List<FlagEnvironmentConfig> findByFlagIdIn(Collection<Long> flagIds);

    @Query("select c from FlagEnvironmentConfig c join fetch c.environment where c.flag.id = :flagId and c.environment.id = :environmentId")
    Optional<FlagEnvironmentConfig> findByFlagIdAndEnvironmentId(Long flagId, Long environmentId);

    @Query("select c from FlagEnvironmentConfig c join fetch c.flag f join fetch c.environment where f.project.id = :projectId")
    List<FlagEnvironmentConfig> findByProjectId(Long projectId);

    /** Everything needed to build an environment snapshot in a single query. */
    @Query("select c from FlagEnvironmentConfig c join fetch c.flag f where c.environment.id = :environmentId and f.archived = false")
    List<FlagEnvironmentConfig> findActiveByEnvironmentId(Long environmentId);
}
