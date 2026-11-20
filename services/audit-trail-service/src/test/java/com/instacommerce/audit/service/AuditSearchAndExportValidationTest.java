package com.instacommerce.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.audit.controller.AdminAuditController;
import com.instacommerce.audit.dto.AuditSearchCriteria;
import com.instacommerce.audit.exception.ApiException;
import com.instacommerce.audit.repository.AuditEventRepository;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AuditSearchAndExportValidationTest {

    @Test
    void searchCriteriaValidatesPaginationDateBoundariesAndFilterSanitization() {
        Instant now = Instant.parse("2026-11-19T12:00:00Z");

        assertThatThrownBy(() -> new AuditSearchCriteria(
            null, null, null, null, null, null, now.minus(Duration.ofDays(1)), now, -1, 20))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("INVALID_PAGINATION"));

        assertThatThrownBy(() -> new AuditSearchCriteria(
            null, null, null, null, null, null, now.minus(Duration.ofDays(1)), now, 0, 0))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("INVALID_PAGINATION"));

        assertThatThrownBy(() -> new AuditSearchCriteria(
            null, null, null, null, null, null, now.minus(Duration.ofDays(1)), now, 0, 101))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("INVALID_PAGINATION"));

        assertThatThrownBy(() -> new AuditSearchCriteria(
            null, null, null, null, null, null, now, now, 0, 20))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("INVALID_RANGE"));

        assertThatThrownBy(() -> new AuditSearchCriteria(
            null, null, null, null, null, null, now.minus(Duration.ofDays(366).plusHours(1)), now, 0, 20))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("INVALID_RANGE"));

        assertThatThrownBy(() -> new AuditSearchCriteria(
            null, "Order\nInjected", null, null, null, null, now.minus(Duration.ofDays(1)), now, 0, 20))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("INVALID_FILTER"));

        AuditSearchCriteria sanitized = new AuditSearchCriteria(
            null, "  Order  ", "   ", " order-service ", null, null,
            now.minus(Duration.ofDays(2)), now, 0, 20);
        assertThat(sanitized.resourceType()).isEqualTo("Order");
        assertThat(sanitized.resourceId()).isNull();
        assertThat(sanitized.sourceService()).isEqualTo("order-service");
    }

    @Test
    void exportRejectsDateRangeExceeding90DaysBeforeTouchingHttpResponseAndEscapesLeadingWhitespaceFormulas() {
        Instant now = Instant.parse("2026-11-19T12:00:00Z");
        Instant tooOld = now.minus(Duration.ofDays(91));

        assertThatThrownBy(() -> AuditSearchCriteria.forExport(
            null, null, null, null, null, null, tooOld, now))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("INVALID_EXPORT_RANGE"));

        AuditExportService exportService = new AuditExportService(
            mock(AuditEventRepository.class), new ObjectMapper());
        HttpServletResponse response = mock(HttpServletResponse.class);
        AuditSearchCriteria wideQueryCriteria = new AuditSearchCriteria(
            null, null, null, null, null, null, tooOld, now, 0, 50);

        assertThatThrownBy(() -> exportService.exportCsv(wideQueryCriteria, response))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("INVALID_EXPORT_RANGE"));
        verifyNoInteractions(response);

        AdminAuditController controller = new AdminAuditController(
            mock(AuditQueryService.class), exportService);
        assertThatThrownBy(() -> controller.exportCsv(
            null, null, null, null, null, null, tooOld, now, response))
            .isInstanceOf(ApiException.class);
        verifyNoInteractions(response);

        assertThat(AuditExportService.escapeCsv("   =CMD|' /C calc'!A0"))
            .isEqualTo("'   =CMD|' /C calc'!A0");
        assertThat(AuditExportService.escapeCsv("  @SUM(A1:A2)"))
            .isEqualTo("'  @SUM(A1:A2)");
    }
}
