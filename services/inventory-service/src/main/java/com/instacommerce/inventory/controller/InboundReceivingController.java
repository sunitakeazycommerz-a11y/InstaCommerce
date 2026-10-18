package com.instacommerce.inventory.controller;

import com.instacommerce.inventory.dto.request.AsnIngestRequest;
import com.instacommerce.inventory.dto.request.PalletReceiptRequest;
import com.instacommerce.inventory.dto.response.AsnResponse;
import com.instacommerce.inventory.service.InboundReceivingService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping
@Validated
public class InboundReceivingController {

    private final InboundReceivingService receivingService;

    public InboundReceivingController(InboundReceivingService receivingService) {
        this.receivingService = receivingService;
    }

    @PostMapping("/receiving/asn")
    public ResponseEntity<AsnResponse> ingestAsn(@Valid @RequestBody AsnIngestRequest request) {
        return ResponseEntity.ok(receivingService.ingestAsn(request));
    }

    @PostMapping("/receiving/pallets/{barcode}/receive")
    public ResponseEntity<AsnResponse> receivePallet(
            @PathVariable String barcode,
            @RequestBody(required = false) PalletReceiptRequest request) {
        String receivedBy = request != null ? request.receivedBy() : "DARK_STORE_OPERATOR";
        return ResponseEntity.ok(receivingService.receiveByPalletBarcode(barcode, receivedBy));
    }

    @GetMapping("/receiving/asn/{asnNumber}")
    public ResponseEntity<AsnResponse> getAsn(@PathVariable String asnNumber) {
        return ResponseEntity.ok(receivingService.getByAsnNumber(asnNumber));
    }

    @GetMapping("/receiving/pallets/{barcode}")
    public ResponseEntity<AsnResponse> getByPalletBarcode(@PathVariable String barcode) {
        return ResponseEntity.ok(receivingService.getByPalletBarcode(barcode));
    }

    @GetMapping("/stores/{storeId}/receiving/asns")
    public ResponseEntity<List<AsnResponse>> getStoreAsns(@PathVariable UUID storeId) {
        return ResponseEntity.ok(receivingService.getByStoreId(storeId));
    }
}
