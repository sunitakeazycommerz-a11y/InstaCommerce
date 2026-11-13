package com.instacommerce.mobilebff.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.mobilebff.aggregator.HomeAggregator;
import com.instacommerce.mobilebff.dto.HomeResponse;
import com.instacommerce.mobilebff.exception.GlobalExceptionHandler;
import com.instacommerce.mobilebff.security.MobileBffAccessDeniedHandler;
import com.instacommerce.mobilebff.service.CartSyncService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@DisplayName("MobileBffController Unit Tests")
class MobileBffControllerTest {

    private HomeAggregator homeAggregator;
    private CartSyncService cartSyncService;
    private MobileBffController controller;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        homeAggregator = mock(HomeAggregator.class);
        cartSyncService = mock(CartSyncService.class);
        ObjectProvider<CartSyncService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(cartSyncService);
        controller = new MobileBffController(homeAggregator, provider);
    }

    @Test
    @DisplayName("streamCartUpdates allows matching authenticated owner")
    void streamCartUpdatesAllowsOwner() {
        ServerSentEvent<Map<String, Object>> event = ServerSentEvent.<Map<String, Object>>builder()
            .event("cart-updated")
            .data(Map.of("userId", "usr_123", "itemCount", 2))
            .build();
        when(cartSyncService.subscribeToCartUpdates("usr_123")).thenReturn(Flux.just(event));

        Authentication auth = new UsernamePasswordAuthenticationToken(
            "usr_123", "token", List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));

        StepVerifier.create(controller.streamCartUpdates("usr_123", auth))
            .expectNextMatches(sse -> "cart-updated".equals(sse.event())
                && Integer.valueOf(2).equals(sse.data().get("itemCount")))
            .verifyComplete();
    }

    @Test
    @DisplayName("streamCartUpdates allows ROLE_ADMIN for another user")
    void streamCartUpdatesAllowsAdmin() {
        ServerSentEvent<Map<String, Object>> event = ServerSentEvent.<Map<String, Object>>builder()
            .event("cart-updated")
            .data(Map.of("userId", "usr_target"))
            .build();
        when(cartSyncService.subscribeToCartUpdates("usr_target")).thenReturn(Flux.just(event));

        Authentication auth = new UsernamePasswordAuthenticationToken(
            "admin_1", "token", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

        StepVerifier.create(controller.streamCartUpdates("usr_target", auth))
            .expectNextCount(1)
            .verifyComplete();
    }

    @Test
    @DisplayName("streamCartUpdates allows ROLE_INTERNAL_SERVICE for another user")
    void streamCartUpdatesAllowsInternalService() {
        when(cartSyncService.subscribeToCartUpdates("usr_target")).thenReturn(Flux.empty());

        Authentication auth = new UsernamePasswordAuthenticationToken(
            "checkout-orchestrator", "token", List.of(new SimpleGrantedAuthority("ROLE_INTERNAL_SERVICE")));

        StepVerifier.create(controller.streamCartUpdates("usr_target", auth))
            .verifyComplete();
        verify(cartSyncService).subscribeToCartUpdates("usr_target");
    }

    @Test
    @DisplayName("streamCartUpdates denies different user without elevated role")
    void streamCartUpdatesDeniesDifferentUser() {
        Authentication auth = new UsernamePasswordAuthenticationToken(
            "usr_123", "token", List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));

        assertThatThrownBy(() -> controller.streamCartUpdates("usr_other", auth))
            .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(cartSyncService);
    }

    @Test
    @DisplayName("streamCartUpdates denies null, anonymous, or unauthenticated caller")
    void streamCartUpdatesDeniesUnauthenticatedOrAnonymous() {
        assertThatThrownBy(() -> controller.streamCartUpdates("usr_123", null))
            .isInstanceOf(AccessDeniedException.class);

        Authentication anonymous = new AnonymousAuthenticationToken(
            "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
        assertThatThrownBy(() -> controller.streamCartUpdates("usr_123", anonymous))
            .isInstanceOf(AccessDeniedException.class);

        Authentication unauthenticated = UsernamePasswordAuthenticationToken.unauthenticated("usr_123", "token");
        assertThatThrownBy(() -> controller.streamCartUpdates("usr_123", unauthenticated))
            .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(cartSyncService);
    }

    @Test
    @DisplayName("streamCartUpdates returns empty Flux when CartSyncService is absent for authorized caller")
    void streamCartUpdatesHandlesMissingCartSyncService() {
        MobileBffController controllerWithoutCartSync = new MobileBffController(homeAggregator);
        Authentication auth = new UsernamePasswordAuthenticationToken(
            "usr_123", "token", List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));

        StepVerifier.create(controllerWithoutCartSync.streamCartUpdates("usr_123", auth))
            .verifyComplete();
    }

    @Test
    @DisplayName("Controller advice renders 403 ACCESS_DENIED JSON even for SSE Accept header")
    void controllerAdviceRendersJson403ForSseAccept() {
        WebTestClient client = WebTestClient.bindToController(controller)
            .controllerAdvice(new GlobalExceptionHandler(new MobileBffAccessDeniedHandler(new ObjectMapper())))
            .build();

        client.get()
            .uri("/bff/mobile/v1/cart/stream/usr_other")
            .accept(MediaType.TEXT_EVENT_STREAM)
            .exchange()
            .expectStatus().isForbidden()
            .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
            .expectBody()
            .jsonPath("$.code").isEqualTo("ACCESS_DENIED");
    }

    @Test
    @DisplayName("home forwards authenticated userId and ignores anonymous principal")
    void homeResolvesUserIdFromAuthentication() {
        when(homeAggregator.aggregate(any(), eq("store_1")))
            .thenReturn(Mono.just(HomeResponse.of(List.of(), List.of(), Map.of(), Map.of())));

        Authentication auth = new UsernamePasswordAuthenticationToken(
            "usr_123", "token", List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
        StepVerifier.create(controller.home("store_1", auth))
            .expectNextCount(1)
            .verifyComplete();
        verify(homeAggregator).aggregate("usr_123", "store_1");

        StepVerifier.create(controller.home("store_1", null))
            .expectNextCount(1)
            .verifyComplete();
        verify(homeAggregator).aggregate(null, "store_1");
    }
}
