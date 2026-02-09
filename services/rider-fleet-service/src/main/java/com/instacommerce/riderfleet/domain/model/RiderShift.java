package com.instacommerce.riderfleet.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "rider_shifts")
public class RiderShift {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "rider_id", nullable = false)
    private UUID riderId;

    @Column(name = "shift_start", nullable = false)
    private Instant shiftStart;

    @Column(name = "shift_end", nullable = false)
    private Instant shiftEnd;

    @Column(nullable = false)
    private String status = "SCHEDULED";

    @PrePersist
    void prePersist() {
        if (status == null) {
            status = "SCHEDULED";
        }
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getRiderId() {
        return riderId;
    }

    public void setRiderId(UUID riderId) {
        this.riderId = riderId;
    }

    public Instant getShiftStart() {
        return shiftStart;
    }

    public void setShiftStart(Instant shiftStart) {
        this.shiftStart = shiftStart;
    }

    public Instant getShiftEnd() {
        return shiftEnd;
    }

    public void setShiftEnd(Instant shiftEnd) {
        this.shiftEnd = shiftEnd;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
