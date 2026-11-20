package com.instacommerce.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.instacommerce.platform.web.ProblemDetailFactory.FieldViolation;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

@DisplayName("ProblemDetailFactory tests")
class ProblemDetailFactoryTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void buildsProblemDetailWithMetadataAndFieldViolations() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/v1/checkout");
        when(request.getHeader("traceparent"))
                .thenReturn("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");

        List<FieldViolation> errors = List.of(new FieldViolation("walletAmountCents", "must be >= 0"));
        ResponseEntity<ProblemDetail> entity = ProblemDetailFactory.response(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "Invalid checkout payload",
                request,
                errors);

        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(entity.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        ProblemDetail body = entity.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getStatus()).isEqualTo(400);
        assertThat(body.getTitle()).isEqualTo("Bad Request");
        assertThat(body.getDetail()).isEqualTo("Invalid checkout payload");
        assertThat(body.getType()).isEqualTo(URI.create("urn:instacommerce:problem:validation_error"));
        assertThat(body.getInstance()).isEqualTo(URI.create("/api/v1/checkout"));
        assertThat(body.getProperties())
                .containsEntry("code", "VALIDATION_ERROR")
                .containsEntry("traceId", "4bf92f3577b34da6a3ce929d0e0e4736")
                .containsEntry("errors", errors)
                .containsKey("timestamp");
    }

    @Test
    void prefersMdcTraceIdAndHandlesMissingRequestGracefully() {
        MDC.put("traceId", "mdc-trace-123");
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Request-Id")).thenReturn("header-req-999");

        assertThat(ProblemDetailFactory.resolveTraceId(request)).isEqualTo("mdc-trace-123");

        MDC.clear();
        ProblemDetail minimal = ProblemDetailFactory.create(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Missing order");
        assertThat(minimal.getStatus()).isEqualTo(404);
        assertThat(minimal.getInstance()).isNull();
        assertThat(minimal.getProperties())
                .containsEntry("code", "ORDER_NOT_FOUND")
                .doesNotContainKey("traceId")
                .doesNotContainKey("errors");
    }
}
