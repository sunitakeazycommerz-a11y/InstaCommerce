package com.instacommerce.routing.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.instacommerce.routing.domain.model.DeliveryTracking;
import com.instacommerce.routing.dto.response.TrackingResponse;
import com.instacommerce.routing.repository.DeliveryRepository;
import com.instacommerce.routing.repository.DeliveryTrackingRepository;
import com.instacommerce.routing.security.JwtService;
import com.instacommerce.routing.service.DeliveryService;
import com.instacommerce.routing.service.TrackingService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.socket.config.annotation.SockJsServiceRegistration;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.StompWebSocketEndpointRegistration;

class WebSocketConfigSecurityTest {

    @Test
    void stompInterceptorRequiresValidJwtOnConnectAndAuthOnSubscribe() {
        JwtService jwtService = mock(JwtService.class);
        WebSocketConfig config = new WebSocketConfig(jwtService, "https://app.instacommerce.dev");
        ChannelInterceptor interceptor = config.stompJwtChannelInterceptor();
        MessageChannel channel = mock(MessageChannel.class);

        Message<?> unauthConnect = buildStompFrame(StompCommand.CONNECT, null, null);
        assertThatThrownBy(() -> interceptor.preSend(unauthConnect, channel))
            .isInstanceOf(AccessDeniedException.class);

        when(jwtService.validateAccessToken("bad-token")).thenThrow(new JwtException("invalid"));
        Message<?> invalidConnect = buildStompFrame(StompCommand.CONNECT, "Bearer bad-token", null);
        assertThatThrownBy(() -> interceptor.preSend(invalidConnect, channel))
            .isInstanceOf(AccessDeniedException.class);

        Message<?> unauthSubscribe = buildStompFrame(StompCommand.SUBSCRIBE, null, null);
        assertThatThrownBy(() -> interceptor.preSend(unauthSubscribe, channel))
            .isInstanceOf(AccessDeniedException.class);

        Jws<Claims> jws = mock();
        Claims claims = mock(Claims.class);
        when(jws.getPayload()).thenReturn(claims);
        when(claims.getSubject()).thenReturn("user-42");
        Collection<? extends GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        when(jwtService.validateAccessToken("good-token")).thenReturn(jws);
        doReturn(authorities).when(jwtService).extractAuthorities(claims);

        Message<?> validConnect = buildStompFrame(StompCommand.CONNECT, "Bearer good-token", null);
        Message<?> result = interceptor.preSend(validConnect, channel);
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(result);
        assertThat(accessor.getUser()).isNotNull();
        assertThat(accessor.getUser().getName()).isEqualTo("user-42");

        Message<?> authSubscribe = buildStompFrame(StompCommand.SUBSCRIBE, null, accessor.getUser());
        assertThat(interceptor.preSend(authSubscribe, channel)).isNotNull();
    }

    @Test
    void registerStompEndpointsUsesConfiguredOriginPatternsInsteadOfWildcard() {
        JwtService jwtService = mock(JwtService.class);
        WebSocketConfig config = new WebSocketConfig(
            jwtService, "https://app.instacommerce.dev, http://localhost:3000");

        StompEndpointRegistry registry = mock(StompEndpointRegistry.class);
        StompWebSocketEndpointRegistration registration = mock(StompWebSocketEndpointRegistration.class);
        SockJsServiceRegistration sockJs = mock(SockJsServiceRegistration.class);
        when(registry.addEndpoint("/ws/tracking")).thenReturn(registration);
        when(registration.setAllowedOriginPatterns("https://app.instacommerce.dev", "http://localhost:3000"))
            .thenReturn(registration);
        when(registration.withSockJS()).thenReturn(sockJs);

        config.registerStompEndpoints(registry);

        verify(registration).setAllowedOriginPatterns("https://app.instacommerce.dev", "http://localhost:3000");
    }

    @Test
    void trackingServiceClampsPageSizeToOneHundred() {
        DeliveryTrackingRepository trackingRepository = mock(DeliveryTrackingRepository.class);
        DeliveryRepository deliveryRepository = mock(DeliveryRepository.class);
        DeliveryService deliveryService = mock(DeliveryService.class);
        TrackingService trackingService = new TrackingService(
            trackingRepository, deliveryRepository, deliveryService, null);

        UUID deliveryId = UUID.randomUUID();
        DeliveryTracking point = new DeliveryTracking();
        point.setDeliveryId(deliveryId);
        point.setLatitude(new BigDecimal("12.9716"));
        point.setLongitude(new BigDecimal("77.5946"));
        point.setRecordedAt(Instant.now());

        when(trackingRepository.findByDeliveryIdOrderByRecordedAtDesc(any(), any(Pageable.class)))
            .thenAnswer(inv -> new PageImpl<>(List.of(point), inv.getArgument(1), 1));

        Page<TrackingResponse> page = trackingService.getTrackingHistory(deliveryId, PageRequest.of(0, 500));

        assertThat(page.getSize()).isEqualTo(100);
        assertThat(page.getContent()).hasSize(1);
    }

    private static Message<?> buildStompFrame(StompCommand command,
                                              String authorizationHeader,
                                              java.security.Principal user) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setLeaveMutable(true);
        if (authorizationHeader != null) {
            accessor.addNativeHeader("Authorization", authorizationHeader);
        }
        if (user != null) {
            accessor.setUser(user);
        }
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
