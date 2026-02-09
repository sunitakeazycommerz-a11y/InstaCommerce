package com.instacommerce.inventory.service;

import com.instacommerce.inventory.domain.model.Reservation;
import com.instacommerce.inventory.domain.model.ReservationStatus;
import com.instacommerce.inventory.repository.ReservationRepository;
import java.time.Instant;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReservationExpiryJob {
    private static final int BATCH_SIZE = 100;
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
        Instant cutoff = Instant.now();
        List<Reservation> expired;
        do {
            expired = reservationRepository.findByStatusAndExpiresAtBefore(
                ReservationStatus.PENDING,
                cutoff,
                PageRequest.of(0, BATCH_SIZE));
            for (Reservation reservation : expired) {
                reservationService.expireReservation(reservation.getId());
            }
        } while (expired.size() == BATCH_SIZE);
    }
}
