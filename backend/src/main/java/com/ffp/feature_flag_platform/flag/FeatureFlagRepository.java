package com.ffp.feature_flag_platform.flag;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface FeatureFlagRepository extends JpaRepository<FeatureFlag, Long> {

    Optional<FeatureFlag> findByProjectIdAndKey(Long projectId, String key);

    boolean existsByProjectIdAndKey(Long projectId, String key);

    List<FeatureFlag> findByProjectId(Long projectId);

    long countByProjectIdAndArchivedFalse(Long projectId);

    /**
     * Search used by the dashboard. {@code query} matches key or name (case-insensitive);
     * null parameters disable the corresponding filter.
     */
    @Query("""
            select f from FeatureFlag f
            where f.project.id = :projectId
              and f.archived = :archived
              and (:query is null
                   or lower(f.key) like lower(concat('%', cast(:query as string), '%'))
                   or lower(f.name) like lower(concat('%', cast(:query as string), '%')))
              and (:tag is null or :tag member of f.tags)
            """)
    Page<FeatureFlag> search(Long projectId, boolean archived, String query, String tag, Pageable pageable);

    @Query("select distinct t from FeatureFlag f join f.tags t where f.project.id = :projectId order by t")
    List<String> findDistinctTags(Long projectId);
}
