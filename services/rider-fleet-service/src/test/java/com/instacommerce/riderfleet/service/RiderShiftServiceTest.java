package com.instacommerce.riderfleet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.instacommerce.riderfleet.domain.model.RiderShift;
import com.instacommerce.riderfleet.dto.request.CreateShiftRequest;
import com.instacommerce.riderfleet.dto.request.UpdateShiftRequest;
import com.instacommerce.riderfleet.dto.response.ShiftResponse;
import com.instacommerce.riderfleet.exception.InvalidShiftStateException;
import com.instacommerce.riderfleet.exception.RiderNotFoundException;
import com.instacommerce.riderfleet.exception.ShiftNotFoundException;
import com.instacommerce.riderfleet.repository.RiderRepository;
import com.instacommerce.riderfleet.repository.RiderShiftRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RiderShiftServiceTest {

    @Mock private RiderShiftRepository shiftRepository;
    @Mock private RiderRepository riderRepository;

    private RiderShiftService service;
    private UUID riderId;
    private Instant baseTime;

    @BeforeEach
    void setUp() {
        service = new RiderShiftService(shiftRepository, riderRepository);
        riderId = UUID.randomUUID();
        baseTime = Instant.parse("2026-03-20T08:00:00Z");
    }

    @Test
    @DisplayName("createShift saves non-overlapping shift and defaults status to SCHEDULED")
    void createShiftSuccess() {
        Instant end = baseTime.plus(4, ChronoUnit.HOURS);
        when(riderRepository.existsById(riderId)).thenReturn(true);
        when(shiftRepository.findByRiderId(riderId)).thenReturn(List.of());
        when(shiftRepository.save(any(RiderShift.class))).thenAnswer(inv -> {
            RiderShift shift = inv.getArgument(0);
            shift.setId(UUID.randomUUID());
            return shift;
        });

        ShiftResponse response = service.createShift(riderId, new CreateShiftRequest(baseTime, end, null));

        assertThat(response.id()).isNotNull();
        assertThat(response.riderId()).isEqualTo(riderId);
        assertThat(response.status()).isEqualTo("SCHEDULED");
        assertThat(response.shiftStart()).isEqualTo(baseTime);
        assertThat(response.shiftEnd()).isEqualTo(end);
    }

    @Test
    @DisplayName("createShift throws RiderNotFoundException when rider is unknown")
    void createShiftUnknownRider() {
        when(riderRepository.existsById(riderId)).thenReturn(false);

        assertThatThrownBy(() -> service.createShift(
            riderId, new CreateShiftRequest(baseTime, baseTime.plus(2, ChronoUnit.HOURS), "SCHEDULED")))
            .isInstanceOf(RiderNotFoundException.class);
    }

    @Test
    @DisplayName("createShift rejects overlapping scheduled or active shifts")
    void createShiftRejectsOverlap() {
        RiderShift existing = new RiderShift();
        existing.setId(UUID.randomUUID());
        existing.setRiderId(riderId);
        existing.setShiftStart(baseTime);
        existing.setShiftEnd(baseTime.plus(4, ChronoUnit.HOURS));
        existing.setStatus("ACTIVE");

        when(riderRepository.existsById(riderId)).thenReturn(true);
        when(shiftRepository.findByRiderId(riderId)).thenReturn(List.of(existing));

        assertThatThrownBy(() -> service.createShift(
            riderId,
            new CreateShiftRequest(baseTime.plus(2, ChronoUnit.HOURS), baseTime.plus(6, ChronoUnit.HOURS), "SCHEDULED")))
            .isInstanceOf(InvalidShiftStateException.class)
            .hasMessageContaining("overlaps");
    }

    @Test
    @DisplayName("createShift rejects COMPLETED initial status and inverted window")
    void createShiftValidatesStatusAndWindow() {
        when(riderRepository.existsById(riderId)).thenReturn(true);

        assertThatThrownBy(() -> service.createShift(
            riderId, new CreateShiftRequest(baseTime, baseTime, "SCHEDULED")))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> service.createShift(
            riderId, new CreateShiftRequest(baseTime, baseTime.plus(2, ChronoUnit.HOURS), "COMPLETED")))
            .isInstanceOf(InvalidShiftStateException.class);
    }

    @Test
    @DisplayName("listShifts filters by normalized status when provided")
    void listShiftsWithStatusFilter() {
        RiderShift shift = new RiderShift();
        shift.setId(UUID.randomUUID());
        shift.setRiderId(riderId);
        shift.setShiftStart(baseTime);
        shift.setShiftEnd(baseTime.plus(4, ChronoUnit.HOURS));
        shift.setStatus("ACTIVE");

        when(riderRepository.existsById(riderId)).thenReturn(true);
        when(shiftRepository.findByRiderIdAndStatusOrderByShiftStartDesc(riderId, "ACTIVE"))
            .thenReturn(List.of(shift));

        List<ShiftResponse> responses = service.listShifts(riderId, "active");

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).status()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("updateShift transitions SCHEDULED to ACTIVE and rejects invalid transition out of COMPLETED")
    void updateShiftValidatesStateMachine() {
        UUID shiftId = UUID.randomUUID();
        RiderShift scheduled = new RiderShift();
        scheduled.setId(shiftId);
        scheduled.setRiderId(riderId);
        scheduled.setShiftStart(baseTime);
        scheduled.setShiftEnd(baseTime.plus(4, ChronoUnit.HOURS));
        scheduled.setStatus("SCHEDULED");

        when(riderRepository.existsById(riderId)).thenReturn(true);
        when(shiftRepository.findByIdAndRiderId(shiftId, riderId)).thenReturn(Optional.of(scheduled));
        when(shiftRepository.save(any(RiderShift.class))).thenAnswer(inv -> inv.getArgument(0));

        ShiftResponse activated = service.updateShift(riderId, shiftId, new UpdateShiftRequest(null, null, "ACTIVE"));
        assertThat(activated.status()).isEqualTo("ACTIVE");

        scheduled.setStatus("COMPLETED");
        assertThatThrownBy(() -> service.updateShift(riderId, shiftId, new UpdateShiftRequest(null, null, "ACTIVE")))
            .isInstanceOf(InvalidShiftStateException.class);
    }

    @Test
    @DisplayName("updateShift rejects window resize once ACTIVE and throws ShiftNotFoundException when absent")
    void updateShiftGuardsActiveWindowAndMissingShift() {
        UUID shiftId = UUID.randomUUID();
        RiderShift active = new RiderShift();
        active.setId(shiftId);
        active.setRiderId(riderId);
        active.setShiftStart(baseTime);
        active.setShiftEnd(baseTime.plus(4, ChronoUnit.HOURS));
        active.setStatus("ACTIVE");

        when(riderRepository.existsById(riderId)).thenReturn(true);
        when(shiftRepository.findByIdAndRiderId(shiftId, riderId)).thenReturn(Optional.of(active));

        assertThatThrownBy(() -> service.updateShift(
            riderId, shiftId, new UpdateShiftRequest(null, baseTime.plus(5, ChronoUnit.HOURS), null)))
            .isInstanceOf(InvalidShiftStateException.class)
            .hasMessageContaining("SCHEDULED");

        UUID missingId = UUID.randomUUID();
        when(shiftRepository.findByIdAndRiderId(missingId, riderId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.updateShift(riderId, missingId, new UpdateShiftRequest(null, null, "CANCELLED")))
            .isInstanceOf(ShiftNotFoundException.class);
    }

    @Test
    @DisplayName("hasActiveShift enforces start-inclusive and end-exclusive window")
    void hasActiveShiftWindowBounds() {
        RiderShift active = new RiderShift();
        active.setId(UUID.randomUUID());
        active.setRiderId(riderId);
        active.setShiftStart(baseTime);
        active.setShiftEnd(baseTime.plus(2, ChronoUnit.HOURS));
        active.setStatus("ACTIVE");

        when(shiftRepository.findByRiderIdAndStatus(riderId, "ACTIVE")).thenReturn(List.of(active));

        assertThat(service.hasActiveShift(riderId, baseTime)).isTrue();
        assertThat(service.hasActiveShift(riderId, baseTime.plus(1, ChronoUnit.HOURS))).isTrue();
        assertThat(service.hasActiveShift(riderId, baseTime.plus(2, ChronoUnit.HOURS))).isFalse();
    }
}
