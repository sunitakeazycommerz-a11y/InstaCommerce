package com.instacommerce.platform.web;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Clamps and normalizes Spring Data {@link Pageable} inputs to enforce bounded page sizes.
 */
public final class PageableUtils {
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int DEFAULT_MAX_PAGE_SIZE = 100;

    private PageableUtils() {
    }

    public static Pageable sanitize(Pageable pageable) {
        return sanitize(pageable, DEFAULT_PAGE_SIZE, DEFAULT_MAX_PAGE_SIZE);
    }

    public static Pageable sanitize(Pageable pageable, int defaultSize, int maxSize) {
        if (defaultSize < 1) {
            throw new IllegalArgumentException("defaultSize must be >= 1, got " + defaultSize);
        }
        if (maxSize < 1) {
            throw new IllegalArgumentException("maxSize must be >= 1, got " + maxSize);
        }
        if (defaultSize > maxSize) {
            throw new IllegalArgumentException(
                    "defaultSize (" + defaultSize + ") must not exceed maxSize (" + maxSize + ")");
        }
        if (pageable == null) {
            return PageRequest.of(0, defaultSize);
        }
        Sort sort = pageable.getSort();
        if (pageable.isUnpaged()) {
            return PageRequest.of(0, defaultSize, sort);
        }
        int page = Math.max(0, pageable.getPageNumber());
        int requestedSize = pageable.getPageSize();
        int clampedSize = requestedSize <= 0 ? defaultSize : Math.min(requestedSize, maxSize);
        return PageRequest.of(page, clampedSize, sort);
    }
}
