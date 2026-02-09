package com.instacommerce.search.repository;

import com.instacommerce.search.domain.model.TrendingQuery;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface TrendingQueryRepository extends JpaRepository<TrendingQuery, UUID> {

    Optional<TrendingQuery> findByQuery(String query);

    List<TrendingQuery> findTopByOrderByHitCountDesc(org.springframework.data.domain.Pageable pageable);

    @Modifying
    @Query("DELETE FROM TrendingQuery t WHERE t.lastSearchedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
