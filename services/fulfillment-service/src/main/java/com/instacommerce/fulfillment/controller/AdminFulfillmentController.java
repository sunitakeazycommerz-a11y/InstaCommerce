package com.instacommerce.fulfillment.controller;

import com.instacommerce.fulfillment.domain.model.Rider;
import com.instacommerce.fulfillment.dto.mapper.FulfillmentMapper;
import com.instacommerce.fulfillment.dto.request.AssignRiderRequest;
import com.instacommerce.fulfillment.dto.request.CreateRiderRequest;
import com.instacommerce.fulfillment.dto.response.DeliveryResponse;
import com.instacommerce.fulfillment.dto.response.RiderResponse;
import com.instacommerce.fulfillment.repository.RiderRepository;
import com.instacommerce.fulfillment.service.AuditLogService;
import com.instacommerce.fulfillment.service.DeliveryService;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/fulfillment")
@PreAuthorize("hasRole('ADMIN')")
public class AdminFulfillmentController {
    private final RiderRepository riderRepository;
    private final DeliveryService deliveryService;
    private final AuditLogService auditLogService;

    public AdminFulfillmentController(RiderRepository riderRepository,
                                      DeliveryService deliveryService,
                                      AuditLogService auditLogService) {
        this.riderRepository = riderRepository;
        this.deliveryService = deliveryService;
        this.auditLogService = auditLogService;
    }

    @PostMapping("/riders")
    public RiderResponse createRider(@Valid @RequestBody CreateRiderRequest request) {
        Rider rider = new Rider();
        rider.setName(request.name());
        rider.setPhone(request.phone());
        rider.setStoreId(request.storeId());
        Rider saved = riderRepository.save(rider);
        auditLogService.log(null,
            "RIDER_CREATED",
            "Rider",
            saved.getId().toString(),
            Map.of("storeId", saved.getStoreId()));
        return FulfillmentMapper.toRiderResponse(saved);
    }

    @GetMapping("/riders")
    public List<RiderResponse> listRiders(@RequestParam(required = false) String storeId) {
        List<Rider> riders = storeId == null
            ? riderRepository.findAll()
            : riderRepository.findByStoreId(storeId);
        return riders.stream().map(FulfillmentMapper::toRiderResponse).toList();
    }

    @PostMapping("/orders/{orderId}/assign")
    public DeliveryResponse assignRider(@PathVariable UUID orderId, @Valid @RequestBody AssignRiderRequest request) {
        DeliveryResponse response = deliveryService.assignRider(orderId, request.riderId(), request.estimatedMinutes());
        auditLogService.log(null,
            "RIDER_ASSIGNED",
            "Delivery",
            orderId.toString(),
            Map.of("orderId", orderId, "riderId", request.riderId()));
        return response;
    }
}
