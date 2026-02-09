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
    @Query(value = """
            INSERT INTO trending_queries (query, hit_count, last_searched_at)
            VALUES (:query, 1, now())
            ON CONFLICT (query) DO UPDATE
            SET hit_count = trending_queries.hit_count + 1,
                last_searched_at = now()
            """, nativeQuery = true)
    int upsertHit(@Param("query") String query);

    @Modifying
    @Query("DELETE FROM TrendingQuery t WHERE t.lastSearchedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
