package com.instacommerce.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.audit.domain.model.AuditEvent;
import com.instacommerce.audit.dto.AuditSearchCriteria;
import com.instacommerce.audit.repository.AuditEventRepository;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor.SpecificationFluentQuery;

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

        when(repository.findBy(ArgumentMatchers.<Specification<AuditEvent>>any(), any()))
                .thenReturn(List.of(event));

        StringWriter buffer = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(buffer));

        AuditExportService service = new AuditExportService(repository, new ObjectMapper());
        service.exportCsv(new AuditSearchCriteria(null, null, null, null, null, null, null, null, 0, 20), response);

        String csv = buffer.toString();
        assertThat(csv).startsWith("id,event_type,source_service,");
        assertThat(csv).contains("'=1+1");
        assertThat(csv).contains("\"Agent\r\nNext\"");
    }

    @Test
    void exportCsv_usesKeysetLimitQueryWithoutCountAndClearsEntityManagerWhenPresent() throws Exception {
        AuditEvent event = AuditEvent.builder()
                .id(UUID.randomUUID())
                .eventType("PAYMENT_CAPTURED")
                .sourceService("payment-service")
                .action("CAPTURE")
                .createdAt(Instant.parse("2026-03-01T11:00:00Z"))
                .build();

        SpecificationFluentQuery<AuditEvent> fluentQuery = mock();
        when(fluentQuery.sortBy(any(Sort.class))).thenReturn(fluentQuery);
        when(fluentQuery.limit(500)).thenReturn(fluentQuery);
        when(fluentQuery.all()).thenReturn(List.of(event));

        when(repository.findBy(ArgumentMatchers.<Specification<AuditEvent>>any(), any()))
                .thenAnswer(inv -> {
                    Function<SpecificationFluentQuery<AuditEvent>, List<AuditEvent>> fn = inv.getArgument(1);
                    return fn.apply(fluentQuery);
                });

        StringWriter buffer = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(buffer));
        EntityManager entityManager = mock(EntityManager.class);

        AuditExportService service = new AuditExportService(repository, new ObjectMapper(), entityManager);
        service.exportCsv(new AuditSearchCriteria(null, null, null, null, null, null, null, null, 0, 20), response);

        verify(repository, never()).findAll(ArgumentMatchers.<Specification<AuditEvent>>any(), any(Pageable.class));
        verify(fluentQuery).sortBy(Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id")));
        verify(fluentQuery).limit(500);
        verify(fluentQuery).all();
        verify(entityManager).clear();
        assertThat(buffer.toString()).contains("PAYMENT_CAPTURED");
    }

    @Test
    void exportCsv_iteratesMultipleBatchesWithoutCountQueries() throws Exception {
        Instant baseTime = Instant.parse("2026-03-01T11:00:00Z");
        List<AuditEvent> firstBatch = IntStream.range(0, 500)
                .mapToObj(i -> AuditEvent.builder()
                        .id(UUID.randomUUID())
                        .eventType("EVENT_" + i)
                        .sourceService("order-service")
                        .action("CREATE")
                        .createdAt(baseTime.plusMillis(i))
                        .build())
                .toList();
        AuditEvent tailEvent = AuditEvent.builder()
                .id(UUID.randomUUID())
                .eventType("EVENT_TAIL")
                .sourceService("order-service")
                .action("CREATE")
                .createdAt(baseTime.plusSeconds(10))
                .build();

        when(repository.findBy(ArgumentMatchers.<Specification<AuditEvent>>any(), any()))
                .thenReturn(firstBatch)
                .thenReturn(List.of(tailEvent));

        StringWriter buffer = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(buffer));
        EntityManager entityManager = mock(EntityManager.class);

        AuditExportService service = new AuditExportService(repository, new ObjectMapper(), entityManager);
        service.exportCsv(new AuditSearchCriteria(null, null, null, null, null, null, null, null, 0, 20), response);

        verify(repository, times(2)).findBy(ArgumentMatchers.<Specification<AuditEvent>>any(), any());
        verify(repository, never()).findAll(ArgumentMatchers.<Specification<AuditEvent>>any(), any(Pageable.class));
        verify(entityManager, times(2)).clear();
        assertThat(buffer.toString()).contains("EVENT_0").contains("EVENT_TAIL");
    }
}
