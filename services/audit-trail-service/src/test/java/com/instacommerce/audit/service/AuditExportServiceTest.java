package com.instacommerce.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.audit.domain.model.AuditEvent;
import com.instacommerce.audit.dto.AuditSearchCriteria;
import com.instacommerce.audit.repository.AuditEventRepository;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

@ExtendWith(MockitoExtension.class)
class AuditExportServiceTest {

    @Mock
    private AuditEventRepository repository;

    @Mock
    private HttpServletResponse response;

    @Test
    void escapeCsv_neutralizesFormulaInjectionAndQuotesControlChars() {
        assertThat(AuditExportService.escapeCsv("=cmd|'/C calc'!A0")).isEqualTo("'=cmd|'/C calc'!A0");
        assertThat(AuditExportService.escapeCsv("+1+1")).isEqualTo("'+1+1");
        assertThat(AuditExportService.escapeCsv("-1+1")).isEqualTo("'-1+1");
        assertThat(AuditExportService.escapeCsv("@SUM")).isEqualTo("'@SUM");
        assertThat(AuditExportService.escapeCsv("@SUM(1,2)")).isEqualTo("\"'@SUM(1,2)\"");
        assertThat(AuditExportService.escapeCsv("\r=1+1")).isEqualTo("\"'\r=1+1\"");
        assertThat(AuditExportService.escapeCsv("hello, \"world\"")).isEqualTo("\"hello, \"\"world\"\"\"");
    }

    @Test
    void exportCsv_streamsHeaderAndEscapedRows() throws Exception {
        AuditEvent event = AuditEvent.builder()
                .id(UUID.randomUUID())
                .eventType("ORDER_CREATED")
                .sourceService("order-service")
                .actorType("USER")
                .resourceType("Order")
                .resourceId("=1+1")
                .action("CREATE")
                .userAgent("Agent\r\nNext")
                .createdAt(Instant.parse("2026-03-01T10:00:00Z"))
                .build();

        when(repository.findAll(ArgumentMatchers.<Specification<AuditEvent>>any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(event)));

        StringWriter buffer = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(buffer));

        AuditExportService service = new AuditExportService(repository, new ObjectMapper());
        service.exportCsv(new AuditSearchCriteria(null, null, null, null, null, null, null, null, 0, 20), response);

        String csv = buffer.toString();
        assertThat(csv).startsWith("id,event_type,source_service,");
        assertThat(csv).contains("'=1+1");
        assertThat(csv).contains("\"Agent\r\nNext\"");
    }
}
