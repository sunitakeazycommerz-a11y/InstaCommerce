package com.instacommerce.inventory.service;

import com.instacommerce.inventory.domain.model.Reservation;
import com.instacommerce.inventory.domain.model.ReservationStatus;
import com.instacommerce.inventory.repository.ReservationRepository;
import java.time.Instant;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReservationExpiryJob {
    private final ReservationRepository reservationRepository;
    private final ReservationService reservationService;

    public ReservationExpiryJob(ReservationRepository reservationRepository,
                                ReservationService reservationService) {
        this.reservationRepository = reservationRepository;
        this.reservationService = reservationService;
    }

    @Scheduled(fixedRateString = "${reservation.expiry-check-interval-ms:30000}")
    @SchedulerLock(name = "reservationExpiry", lockAtLeastFor = "30s", lockAtMostFor = "5m")
    public void expireStaleReservations() {
        List<Reservation> expired = reservationRepository
            .findByStatusAndExpiresAtBefore(ReservationStatus.PENDING, Instant.now());
        for (Reservation reservation : expired) {
            reservationService.expireReservation(reservation.getId());
        }
    }
}
