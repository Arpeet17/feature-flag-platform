package com.ffp.feature_flag_platform.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AuditRepository extends JpaRepository<AuditEntry, Long> {

    @Query("""
            select a from AuditEntry a
            where a.projectKey = :projectKey
              and (:resourceType is null or a.resourceType = :resourceType)
              and (:resourceKey is null or a.resourceKey = :resourceKey)
              and (:environmentKey is null or a.environmentKey = :environmentKey)
            """)
    Page<AuditEntry> search(String projectKey, AuditEntry.ResourceType resourceType, String resourceKey,
                            String environmentKey, Pageable pageable);
}
