package com.instacommerce.inventory.controller;

import com.instacommerce.inventory.dto.request.CancelRequest;
import com.instacommerce.inventory.dto.request.ConfirmRequest;
import com.instacommerce.inventory.dto.request.ReserveRequest;
import com.instacommerce.inventory.dto.response.ReserveResponse;
import com.instacommerce.inventory.service.ReservationService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/inventory", "/api/inventory"})
public class ReservationController {
    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @PostMapping({"/reserve", "/reservations"})
    public ReserveResponse reserve(@Valid @RequestBody ReserveRequest request) {
        return reservationService.reserve(request);
    }

    @PostMapping("/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirm(@Valid @RequestBody ConfirmRequest request) {
        reservationService.confirm(request.reservationId());
    }

    @PostMapping("/reservations/{reservationId}/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirmById(@PathVariable UUID reservationId) {
        reservationService.confirm(reservationId);
    }

    @PostMapping("/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@Valid @RequestBody CancelRequest request) {
        reservationService.cancel(request.reservationId());
    }

    @DeleteMapping("/reservations/{reservationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void releaseById(@PathVariable UUID reservationId) {
        reservationService.cancel(reservationId);
    }
}

