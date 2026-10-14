package com.ffp.feature_flag_platform.rollout;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RolloutHistoryRepository extends JpaRepository<RolloutHistory, Long> {

    List<RolloutHistory> findByRolloutIdOrderByAtAscIdAsc(Long rolloutId);
}
