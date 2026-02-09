package com.instacommerce.search.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "trending_queries")
public class TrendingQuery {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 512)
    private String query;

    @Column(name = "hit_count", nullable = false)
    private long hitCount = 1;

    @Column(name = "last_searched_at", nullable = false)
    private Instant lastSearchedAt;

    protected TrendingQuery() {
    }

    public TrendingQuery(String query) {
        this.query = query;
        this.hitCount = 1;
        this.lastSearchedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getQuery() {
        return query;
    }

    public long getHitCount() {
        return hitCount;
    }

    public void incrementHitCount() {
        this.hitCount++;
        this.lastSearchedAt = Instant.now();
    }

    public Instant getLastSearchedAt() {
        return lastSearchedAt;
    }
}
