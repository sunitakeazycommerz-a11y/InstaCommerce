package com.instacommerce.routing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.routing.config.JacksonConfig;
import com.instacommerce.routing.config.RoutingProperties;
import com.instacommerce.routing.config.SecurityConfig;
import com.instacommerce.routing.consumer.FulfillmentEventConsumer;
import com.instacommerce.routing.controller.AdminRoutingController;
import com.instacommerce.routing.controller.DeliveryController;
import com.instacommerce.routing.controller.TrackingController;
import com.instacommerce.routing.domain.model.Delivery;
import com.instacommerce.routing.domain.model.DeliveryStatus;
import com.instacommerce.routing.domain.model.WeatherCondition;
import com.instacommerce.routing.dto.response.DeliveryResponse;
import com.instacommerce.routing.exception.GlobalExceptionHandler;
import com.instacommerce.routing.exception.TraceIdProvider;
import com.instacommerce.platform.security.InternalServiceSecurityConfiguration;
import com.instacommerce.routing.repository.DeliveryRepository;
import com.instacommerce.routing.repository.RoutingBarrierRepository;
import com.instacommerce.routing.repository.TrafficCongestionFeedRepository;
import com.instacommerce.routing.repository.WeatherConditionRepository;
import com.instacommerce.routing.security.JwtAuthenticationFilter;
import com.instacommerce.routing.security.JwtService;
import com.instacommerce.routing.security.RestAccessDeniedHandler;
import com.instacommerce.routing.security.RestAuthenticationEntryPoint;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = {AdminRoutingController.class, DeliveryController.class, TrackingController.class})
@Import({
    SecurityConfig.class,
    InternalServiceSecurityConfiguration.class,
    JwtAuthenticationFilter.class,
    RestAuthenticationEntryPoint.class,
    RestAccessDeniedHandler.class,
    TraceIdProvider.class,
    JacksonConfig.class,
    GlobalExceptionHandler.class,
    ConcurrentMapCacheManager.class
})
@TestPropertySource(properties = {
    "internal.service.token=test-routing-token"
})
class RoutingRemediationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private RoutingBarrierRepository barrierRepository;

    @MockitoBean
    private WeatherConditionRepository weatherRepository;

    @MockitoBean
    private TrafficCongestionFeedRepository trafficFeedRepository;

    @MockitoBean
    private DeliveryService deliveryService;

    @MockitoBean
    private ETAService etaService;

    @MockitoBean
    private TrackingService trackingService;

    @Test
    @WithMockUser(username = "customer-1", roles = {"USER"})
    @DisplayName("SecurityConfig enforces ADMIN on /admin/routing/** and RIDER/ADMIN/INTERNAL_SERVICE on /tracking/location")
    void securityConfigEnforcesAdminAndTrackingRoles() throws Exception {
        UUID deliveryId = UUID.randomUUID();
        mockMvc.perform(post("/admin/routing/traffic")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"zoneId\":\"Z1\",\"congestionFactor\":1.2}"))
            .andExpect(status().isForbidden());

        mockMvc.perform(post("/tracking/location")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deliveryId\":\"" + deliveryId + "\",\"latitude\":12.97,\"longitude\":77.59}"))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("FulfillmentEventConsumer handles OrderDispatched and OrderDelivered to advance Delivery state")
    void fulfillmentConsumerAdvancesDeliveryState() throws Exception {
        DeliveryService localDeliveryService = Mockito.mock(DeliveryService.class);
        FulfillmentEventConsumer consumer = new FulfillmentEventConsumer(localDeliveryService, new ObjectMapper());

        UUID orderId = UUID.randomUUID();
        UUID riderId = UUID.randomUUID();
        consumer.handleFulfillmentEvent("""
            {"eventType":"OrderDispatched","payload":{"orderId":"%s","riderId":"%s"}}
            """.formatted(orderId, riderId));
        verify(localDeliveryService).advanceOrderDispatched(orderId, riderId);

        consumer.handleFulfillmentEvent("""
            {"eventType":"OrderDelivered","payload":{"orderId":"%s"}}
            """.formatted(orderId));
        verify(localDeliveryService).advanceOrderDelivered(orderId);
    }

    @Test
    @DisplayName("DeliveryController.getETA delegates storeId and vehicleType to apply weather multipliers")
    void deliveryControllerEtaUsesStoreWeatherAndConfidence() throws Exception {
        UUID deliveryId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        DeliveryResponse delivery = new DeliveryResponse(
            deliveryId, UUID.randomUUID(), UUID.randomUUID(), storeId,
            new BigDecimal("12.90"), new BigDecimal("77.50"),
            new BigDecimal("12.92"), new BigDecimal("77.52"),
            DeliveryStatus.EN_ROUTE, 12, null, new BigDecimal("2.0"),
            null, null, null, null, false, false, 0, "MOTORCYCLE", false);

        RoutingBarrierRepository br = Mockito.mock(RoutingBarrierRepository.class);
        WeatherConditionRepository wr = Mockito.mock(WeatherConditionRepository.class);
        TrafficCongestionFeedRepository tr = Mockito.mock(TrafficCongestionFeedRepository.class);
        WeatherCondition monsoon = new WeatherCondition();
        monsoon.setStoreId(storeId);
        monsoon.setSpeedPenaltyMultiplier(new BigDecimal("2.00"));
        when(br.findByIsActiveTrue()).thenReturn(List.of());
        when(wr.findByStoreId(storeId)).thenReturn(Optional.of(monsoon));
        when(tr.findByZoneId(any())).thenReturn(Optional.empty());

        ETAService realEta = new ETAService(new RoutingProperties(), br, wr, tr);
        var withWeather = realEta.calculateETA(12.90, 77.50, 12.92, 77.52, storeId, "MOTORCYCLE", 0.0);
        var withoutWeather = realEta.calculateETA(12.90, 77.50, 12.92, 77.52);
        assertThat(withWeather.estimatedMinutes()).isGreaterThan(withoutWeather.estimatedMinutes());

        DeliveryRepository repo = Mockito.mock(DeliveryRepository.class);
        Delivery persisted = new Delivery();
        persisted.setId(deliveryId);
        persisted.setOrderId(delivery.orderId());
        persisted.setRiderId(delivery.riderId());
        persisted.setStatus(DeliveryStatus.RIDER_ASSIGNED);
        when(repo.findByOrderId(delivery.orderId())).thenReturn(Optional.of(persisted));
        when(repo.findById(deliveryId)).thenReturn(Optional.of(persisted));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        OutboxService outbox = Mockito.mock(OutboxService.class);
        DeliveryService ds = new DeliveryService(repo, realEta, outbox, new RoutingProperties());
        ds.advanceOrderDispatched(delivery.orderId(), delivery.riderId());
        verify(outbox, Mockito.times(2)).publish(eq("Delivery"), eq(deliveryId.toString()),
            eq("DeliveryStatusUpdated"), any());
    }
}
