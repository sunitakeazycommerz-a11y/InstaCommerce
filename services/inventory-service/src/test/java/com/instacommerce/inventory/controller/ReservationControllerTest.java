package com.instacommerce.inventory.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.instacommerce.inventory.dto.request.InventoryItemRequest;
import com.instacommerce.inventory.dto.request.ReserveRequest;
import com.instacommerce.inventory.dto.response.ReserveResponse;
import com.instacommerce.inventory.service.ReservationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationControllerTest {

    @Mock
    private ReservationService reservationService;

    @InjectMocks
    private ReservationController reservationController;

    private final UUID reservationId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();

    @Test
    void reserveReturnsResponseWithReservedTrue() {
        ReserveResponse mockResponse = new ReserveResponse(reservationId, Instant.now(), List.of());
        when(reservationService.reserve(any(ReserveRequest.class))).thenReturn(mockResponse);

        ReserveRequest request = new ReserveRequest(null, null, List.of(new InventoryItemRequest(productId, 2)));
        ReserveResponse response = reservationController.reserve(request);

        assertThat(response.reservationId()).isEqualTo(reservationId);
        assertThat(response.reserved()).isTrue();
    }

    @Test
    void confirmByIdInvokesService() {
        reservationController.confirmById(reservationId);
        verify(reservationService).confirm(reservationId);
    }

    @Test
    void releaseByIdInvokesServiceCancel() {
        reservationController.releaseById(reservationId);
        verify(reservationService).cancel(reservationId);
    }

    @Test
    void serializesReservedFieldInJson() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());

        ReserveResponse response = new ReserveResponse(reservationId, Instant.parse("2026-10-17T12:00:00Z"), List.of());
        String json = mapper.writeValueAsString(response);

        assertThat(json).contains("\"reserved\":true");
        assertThat(json).contains(reservationId.toString());
    }
}
