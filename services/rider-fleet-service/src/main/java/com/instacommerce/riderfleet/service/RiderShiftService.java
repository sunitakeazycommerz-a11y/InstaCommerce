package com.instacommerce.riderfleet.service;

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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RiderShiftService {

    private static final Set<String> VALID_STATUSES = Set.of("SCHEDULED", "ACTIVE", "COMPLETED", "CANCELLED");
    private static final Set<String> OVERLAP_RELEVANT_STATUSES = Set.of("SCHEDULED", "ACTIVE");
    private static final Map<String, Set<String>> ALLOWED_TRANSITIONS = Map.of(
        "SCHEDULED", Set.of("SCHEDULED", "ACTIVE", "CANCELLED"),
        "ACTIVE", Set.of("ACTIVE", "COMPLETED", "CANCELLED"),
        "COMPLETED", Set.of("COMPLETED"),
        "CANCELLED", Set.of("CANCELLED")
    );

    private final RiderShiftRepository shiftRepository;
    private final RiderRepository riderRepository;

    public RiderShiftService(RiderShiftRepository shiftRepository) {
        this(shiftRepository, null);
    }

    @Autowired
    public RiderShiftService(RiderShiftRepository shiftRepository, RiderRepository riderRepository) {
        this.shiftRepository = shiftRepository;
        this.riderRepository = riderRepository;
    }

    @Transactional(readOnly = true)
    public boolean hasActiveShift(UUID riderId, Instant at) {
        List<RiderShift> activeShifts = shiftRepository.findByRiderIdAndStatus(riderId, "ACTIVE");
        for (RiderShift shift : activeShifts) {
            if (!at.isBefore(shift.getShiftStart()) && at.isBefore(shift.getShiftEnd())) {
                return true;
            }
        }
        return false;
    }

    @Transactional
    public ShiftResponse createShift(UUID riderId, CreateShiftRequest request) {
        ensureRiderExists(riderId);
        Instant start = request.shiftStart();
        Instant end = request.shiftEnd();
        validateWindow(start, end);

        String status = normalizeStatus(request.status(), "SCHEDULED");
        if (!"SCHEDULED".equals(status) && !"ACTIVE".equals(status)) {
            throw new InvalidShiftStateException("New shifts must start in SCHEDULED or ACTIVE status");
        }
        assertNoOverlap(riderId, null, start, end);

        RiderShift shift = new RiderShift();
        shift.setRiderId(riderId);
        shift.setShiftStart(start);
        shift.setShiftEnd(end);
        shift.setStatus(status);
        return toResponse(shiftRepository.save(shift));
    }

    @Transactional(readOnly = true)
    public List<ShiftResponse> listShifts(UUID riderId, String statusFilter) {
        ensureRiderExists(riderId);
        List<RiderShift> shifts;
        if (statusFilter != null && !statusFilter.isBlank()) {
            String normalized = normalizeStatus(statusFilter, null);
            shifts = shiftRepository.findByRiderIdAndStatusOrderByShiftStartDesc(riderId, normalized);
        } else {
            shifts = shiftRepository.findByRiderIdOrderByShiftStartDesc(riderId);
        }
        return shifts.stream().map(this::toResponse).toList();
    }

    @Transactional
    public ShiftResponse updateShift(UUID riderId, UUID shiftId, UpdateShiftRequest request) {
        ensureRiderExists(riderId);
        RiderShift shift = shiftRepository.findByIdAndRiderId(shiftId, riderId)
            .orElseThrow(() -> new ShiftNotFoundException(shiftId.toString()));

        String currentStatus = normalizeStatus(shift.getStatus(), "SCHEDULED");
        String targetStatus = request.status() != null
            ? normalizeStatus(request.status(), currentStatus)
            : currentStatus;

        Set<String> allowedTargets = ALLOWED_TRANSITIONS.getOrDefault(currentStatus, Set.of());
        if (!allowedTargets.contains(targetStatus)) {
            throw new InvalidShiftStateException(
                "Cannot transition shift from " + currentStatus + " to " + targetStatus);
        }

        Instant newStart = request.shiftStart() != null ? request.shiftStart() : shift.getShiftStart();
        Instant newEnd = request.shiftEnd() != null ? request.shiftEnd() : shift.getShiftEnd();
        boolean windowChanged = !newStart.equals(shift.getShiftStart()) || !newEnd.equals(shift.getShiftEnd());
        if (windowChanged) {
            if (!"SCHEDULED".equals(currentStatus)) {
                throw new InvalidShiftStateException(
                    "Shift window can only be modified while status is SCHEDULED");
            }
            validateWindow(newStart, newEnd);
            if (OVERLAP_RELEVANT_STATUSES.contains(targetStatus)) {
                assertNoOverlap(riderId, shiftId, newStart, newEnd);
            }
            shift.setShiftStart(newStart);
            shift.setShiftEnd(newEnd);
        }

        shift.setStatus(targetStatus);
        return toResponse(shiftRepository.save(shift));
    }

    private void ensureRiderExists(UUID riderId) {
        if (riderRepository != null && !riderRepository.existsById(riderId)) {
            throw new RiderNotFoundException(riderId.toString());
        }
    }

    private void validateWindow(Instant start, Instant end) {
        if (start == null || end == null || !end.isAfter(start)) {
            throw new IllegalArgumentException("shiftEnd must be strictly after shiftStart");
        }
    }

    private void assertNoOverlap(UUID riderId, UUID excludedShiftId, Instant start, Instant end) {
        List<RiderShift> existing = shiftRepository.findByRiderId(riderId);
        for (RiderShift other : existing) {
            if (excludedShiftId != null && excludedShiftId.equals(other.getId())) {
                continue;
            }
            if (!OVERLAP_RELEVANT_STATUSES.contains(other.getStatus())) {
                continue;
            }
            if (other.getShiftStart().isBefore(end) && other.getShiftEnd().isAfter(start)) {
                throw new InvalidShiftStateException("Shift window overlaps with an existing shift");
            }
        }
    }

    private String normalizeStatus(String raw, String defaultStatus) {
        if (raw == null || raw.isBlank()) {
            return defaultStatus;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if (!VALID_STATUSES.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported shift status: " + raw);
        }
        return normalized;
    }

    private ShiftResponse toResponse(RiderShift shift) {
        return new ShiftResponse(
            shift.getId(),
            shift.getRiderId(),
            shift.getShiftStart(),
            shift.getShiftEnd(),
            shift.getStatus()
        );
    }
}
