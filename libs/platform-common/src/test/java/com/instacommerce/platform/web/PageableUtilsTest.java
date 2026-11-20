package com.instacommerce.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

@DisplayName("PageableUtils tests")
class PageableUtilsTest {

    @Test
    void defaultsNullAndUnpagedInputsSafelyWhilePreservingSort() {
        Pageable fromNull = PageableUtils.sanitize(null, 25, 100);
        assertThat(fromNull.getPageNumber()).isZero();
        assertThat(fromNull.getPageSize()).isEqualTo(25);
        assertThat(fromNull.getSort()).isEqualTo(Sort.unsorted());

        Sort sort = Sort.by(Sort.Order.desc("createdAt"));
        Pageable fromUnpaged = PageableUtils.sanitize(Pageable.unpaged(sort), 15, 50);
        assertThat(fromUnpaged.isPaged()).isTrue();
        assertThat(fromUnpaged.getPageNumber()).isZero();
        assertThat(fromUnpaged.getPageSize()).isEqualTo(15);
        assertThat(fromUnpaged.getSort()).isEqualTo(sort);
    }

    @Test
    void clampsOversizedPageRequestsAndPreservesValidRequests() {
        Sort sort = Sort.by("id");
        Pageable clamped = PageableUtils.sanitize(PageRequest.of(3, 500, sort), 20, 100);
        assertThat(clamped.getPageNumber()).isEqualTo(3);
        assertThat(clamped.getPageSize()).isEqualTo(100);
        assertThat(clamped.getSort()).isEqualTo(sort);

        Pageable unchanged = PageableUtils.sanitize(PageRequest.of(1, 40, sort), 20, 100);
        assertThat(unchanged.getPageNumber()).isEqualTo(1);
        assertThat(unchanged.getPageSize()).isEqualTo(40);

        Pageable defaultSanitize = PageableUtils.sanitize(PageRequest.of(0, 250));
        assertThat(defaultSanitize.getPageSize()).isEqualTo(PageableUtils.DEFAULT_MAX_PAGE_SIZE);
    }

    @Test
    void rejectsInvalidBoundsConfiguration() {
        Pageable page = PageRequest.of(0, 10);
        assertThatThrownBy(() -> PageableUtils.sanitize(page, 0, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PageableUtils.sanitize(page, 10, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PageableUtils.sanitize(page, 50, 20))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
