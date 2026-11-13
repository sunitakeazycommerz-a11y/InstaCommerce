package com.instacommerce.pricing.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.pricing.config.QuoteTokenProperties;
import com.instacommerce.pricing.domain.PriceQuote;
import com.instacommerce.pricing.domain.PriceRule;
import com.instacommerce.pricing.domain.Promotion;
import com.instacommerce.pricing.repository.PriceQuoteRepository;
import com.instacommerce.pricing.repository.PriceRuleRepository;
import com.instacommerce.pricing.service.CouponService;
import com.instacommerce.pricing.service.PricingService;
import com.instacommerce.pricing.service.PromotionService;
import com.instacommerce.pricing.service.QuoteTokenService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@DisplayName("Pricing Tax and Quote Compatibility Tests")
class PricingTaxCompatibilityTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;
    private PriceRuleRepository priceRuleRepository;
    private PromotionService promotionService;
    private PriceQuoteRepository priceQuoteRepository;

    @BeforeEach
    void setUp() {
        priceRuleRepository = mock(PriceRuleRepository.class);
        promotionService = mock(PromotionService.class);
        CouponService couponService = mock(CouponService.class);
        priceQuoteRepository = mock(PriceQuoteRepository.class);

        QuoteTokenProperties quoteProps = new QuoteTokenProperties();
        quoteProps.setHmacSecret("test-pricing-hmac-secret-32-bytes-minimum");
        quoteProps.setTtlSeconds(300);
        quoteProps.setValidationEnabled(true);

        QuoteTokenService quoteTokenService = new QuoteTokenService(priceQuoteRepository, quoteProps);
        PricingService pricingService = new PricingService(
            priceRuleRepository,
            promotionService,
            couponService,
            quoteTokenService);

        PricingController controller = new PricingController(pricingService, couponService, quoteTokenService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("POST /pricing/tax calculates per-item and fallback taxes cleanly")
    void calculateTaxEndpoint() throws Exception {
        UUID milkId = UUID.randomUUID();
        UUID snackId = UUID.randomUUID();
        String body = """
            {
              "items": [
                {"itemId": "%s", "itemName": "Milk", "netAmountCents": 1000, "taxRate": 0.05},
                {"itemId": "%s", "itemName": "Chips", "netAmountCents": 2000}
              ],
              "fallbackTaxRate": 0.18
            }
            """.formatted(milkId, snackId);

        mockMvc.perform(post("/pricing/tax")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalNetCents", is(3000)))
            .andExpect(jsonPath("$.totalTaxCents", is(410)))
            .andExpect(jsonPath("$.totalGrossCents", is(3410)))
            .andExpect(jsonPath("$.lineItems[0].taxCents", is(50)))
            .andExpect(jsonPath("$.lineItems[1].taxCents", is(360)));
    }

    @Test
    @DisplayName("POST /api/pricing/tax calculates tax on /api/pricing prefix")
    void calculateTaxApiPrefixEndpoint() throws Exception {
        UUID itemId = UUID.randomUUID();
        String body = """
            {
              "items": [
                {"itemId": "%s", "itemName": "Coffee", "netAmountCents": 5000}
              ]
            }
            """.formatted(itemId);

        mockMvc.perform(post("/api/pricing/tax")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalNetCents", is(5000)))
            .andExpect(jsonPath("$.totalTaxCents", is(900)))
            .andExpect(jsonPath("$.totalGrossCents", is(5900)));
    }

    @Test
    @DisplayName("POST /pricing/calculate exposes additive tax metadata without breaking quoteToken validation")
    void calculateIncludesAdditiveTaxAndPreservesQuoteValidation() throws Exception {
        UUID productId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Instant now = Instant.now();

        PriceRule rule = new PriceRule();
        rule.setProductId(productId);
        rule.setBasePriceCents(5000L);
        rule.setMultiplier(BigDecimal.ONE);
        rule.setEffectiveFrom(now.minus(1, ChronoUnit.HOURS));
        when(priceRuleRepository.findActiveRuleByProductId(any(), any())).thenReturn(Optional.of(rule));

        Promotion promo = new Promotion();
        promo.setName("FLAT2000");
        promo.setDiscountType("FIXED_AMOUNT");
        promo.setDiscountValue(BigDecimal.valueOf(2000));
        promo.setMinOrderCents(5000L);
        promo.setStartAt(now.minus(1, ChronoUnit.HOURS));
        promo.setEndAt(now.plus(1, ChronoUnit.HOURS));
        when(promotionService.findActivePromotions()).thenReturn(List.of(promo));

        AtomicReference<PriceQuote> savedQuote = new AtomicReference<>();
        when(priceQuoteRepository.save(any(PriceQuote.class))).thenAnswer(inv -> {
            PriceQuote quote = inv.getArgument(0);
            if (quote.getId() == null) {
                quote.setId(UUID.randomUUID());
            }
            savedQuote.set(quote);
            return quote;
        });
        when(priceQuoteRepository.findById(any(UUID.class))).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            PriceQuote quote = savedQuote.get();
            if (quote != null && id.equals(quote.getId())) {
                return Optional.of(quote);
            }
            return Optional.empty();
        });

        String calcBody = """
            {
              "items": [{"productId": "%s", "quantity": 2}],
              "userId": "%s"
            }
            """.formatted(productId, userId);

        MvcResult calcResult = mockMvc.perform(post("/pricing/calculate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(calcBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.subtotalCents", is(10000)))
            .andExpect(jsonPath("$.discountCents", is(2000)))
            .andExpect(jsonPath("$.totalCents", is(8000)))
            .andExpect(jsonPath("$.estimatedTaxCents", is(1440)))
            .andExpect(jsonPath("$.taxRate", is(0.18)))
            .andReturn();

        JsonNode json = objectMapper.readTree(calcResult.getResponse().getContentAsString());
        String quoteId = json.get("quoteId").asText();
        String quoteToken = json.get("quoteToken").asText();

        String validateBody = """
            {
              "quoteId": "%s",
              "quoteToken": "%s",
              "totalCents": 8000,
              "subtotalCents": 10000,
              "discountCents": 2000
            }
            """.formatted(quoteId, quoteToken);

        mockMvc.perform(post("/pricing/quotes/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validateBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.valid", is(true)));
    }
}
