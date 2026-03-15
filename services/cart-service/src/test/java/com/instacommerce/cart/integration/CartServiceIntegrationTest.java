package com.instacommerce.cart.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.instacommerce.cart.CartServiceApplication;
import com.instacommerce.cart.client.PricingClient;
import com.instacommerce.cart.dto.request.AddItemRequest;
import com.instacommerce.cart.dto.response.CartResponse;
import com.instacommerce.cart.repository.CartRepository;
import com.instacommerce.cart.service.CartService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests for {@link CartService} proving item addition, quantity update,
 * item removal, and cart clearing against a real PostgreSQL instance with Flyway migrations.
 */
@SpringBootTest(
    classes = CartServiceApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers(disabledWithoutDocker = true)
class CartServiceIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @MockitoBean
    private PricingClient pricingClient;

    @Autowired
    private CartService cartService;

    @Autowired
    private CartRepository cartRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.execute("""
            TRUNCATE TABLE
                outbox_events,
                cart_items,
                carts,
                shedlock
            RESTART IDENTITY CASCADE
            """);
    }

    // -- shouldAddItemToCart -------------------------------------------------------

    @Test
    void shouldAddItemToCart() {
        UUID userId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();

        when(pricingClient.getPrice(productId))
            .thenReturn(new PricingClient.PriceResponse(productId, 5000L, "INR"));

        AddItemRequest request = new AddItemRequest(productId, "Milk 500ml", 5000L, 2);
        CartResponse response = cartService.addItem(userId, request);

        assertThat(response).isNotNull();
        assertThat(response.userId()).isEqualTo(userId);
        assertThat(response.cartId()).isNotNull();
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).productId()).isEqualTo(productId);
        assertThat(response.items().get(0).productName()).isEqualTo("Milk 500ml");
        assertThat(response.items().get(0).unitPriceCents()).isEqualTo(5000L);
        assertThat(response.items().get(0).quantity()).isEqualTo(2);
        assertThat(response.subtotalCents()).isEqualTo(10000L);

        // Verify persisted to DB
        assertThat(cartRepository.findByUserId(userId)).isPresent();
    }

    // -- shouldUpdateItemQuantity -------------------------------------------------

    @Test
    void shouldUpdateItemQuantity() {
        UUID userId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();

        when(pricingClient.getPrice(productId))
            .thenReturn(new PricingClient.PriceResponse(productId, 5000L, "INR"));

        cartService.addItem(userId, new AddItemRequest(productId, "Milk 500ml", 5000L, 2));

        CartResponse response = cartService.updateQuantity(userId, productId, 5);

        assertThat(response).isNotNull();
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).quantity()).isEqualTo(5);
        assertThat(response.items().get(0).unitPriceCents()).isEqualTo(5000L);
        assertThat(response.subtotalCents()).isEqualTo(25000L);
    }

    // -- shouldRemoveItemFromCart --------------------------------------------------

    @Test
    void shouldRemoveItemFromCart() {
        UUID userId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();

        when(pricingClient.getPrice(productId))
            .thenReturn(new PricingClient.PriceResponse(productId, 5000L, "INR"));

        cartService.addItem(userId, new AddItemRequest(productId, "Milk 500ml", 5000L, 2));

        CartResponse response = cartService.removeItem(userId, productId);

        assertThat(response).isNotNull();
        assertThat(response.items()).isEmpty();
        assertThat(response.subtotalCents()).isZero();
    }

    // -- shouldClearCart -----------------------------------------------------------

    @Test
    void shouldClearCart() {
        UUID userId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();

        when(pricingClient.getPrice(productId))
            .thenReturn(new PricingClient.PriceResponse(productId, 5000L, "INR"));

        cartService.addItem(userId, new AddItemRequest(productId, "Milk 500ml", 5000L, 2));

        cartService.clearCart(userId);

        CartResponse response = cartService.getCart(userId);
        assertThat(response.items()).isEmpty();
        assertThat(response.cartId()).isNull();
        assertThat(response.subtotalCents()).isZero();
    }
}
