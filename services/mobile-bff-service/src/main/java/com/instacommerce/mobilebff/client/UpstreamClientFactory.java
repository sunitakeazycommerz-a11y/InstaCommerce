package com.instacommerce.mobilebff.client;

import com.instacommerce.mobilebff.config.MobileBffProperties;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

@Component
public class UpstreamClientFactory {

    private static final String HEADER_INTERNAL_SERVICE = "X-Internal-Service";
    private static final String HEADER_INTERNAL_TOKEN = "X-Internal-Token";

    private final WebClient catalogClient;
    private final WebClient searchClient;
    private final WebClient featureFlagClient;
    private final WebClient cartClient;
    private final WebClient orderClient;
    private final WebClient identityClient;
    private final WebClient walletLoyaltyClient;
    private final WebClient pricingClient;
    private final WebClient inventoryClient;
    private final WebClient fulfillmentClient;
    private final WebClient routingEtaClient;
    private final WebClient checkoutClient;
    private final String internalServiceName;
    private final String internalServiceToken;

    public UpstreamClientFactory(WebClient.Builder webClientBuilder, MobileBffProperties properties) {
        this(webClientBuilder, properties, "mobile-bff-service", "");
    }

    @Autowired
    public UpstreamClientFactory(
            WebClient.Builder webClientBuilder,
            MobileBffProperties properties,
            @Value("${internal.service.name:${spring.application.name:mobile-bff-service}}") String internalServiceName,
            @Value("${internal.service.token:}") String internalServiceToken) {
        this.internalServiceName = internalServiceName;
        this.internalServiceToken = internalServiceToken;
        var upstream = properties.getUpstream();
        int timeoutMs = upstream.getTimeoutMs();

        ConnectionProvider connectionProvider = ConnectionProvider.builder("mobile-bff-upstream")
            .maxIdleTime(Duration.ofSeconds(500))
            .evictInBackground(Duration.ofSeconds(60))
            .build();

        HttpClient httpClient = HttpClient.create(connectionProvider)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeoutMs)
            .responseTimeout(Duration.ofMillis(timeoutMs))
            .doOnConnected(conn -> conn
                .addHandlerLast(new ReadTimeoutHandler(timeoutMs, TimeUnit.MILLISECONDS))
                .addHandlerLast(new WriteTimeoutHandler(timeoutMs, TimeUnit.MILLISECONDS)));

        ReactorClientHttpConnector connector = new ReactorClientHttpConnector(httpClient);

        this.catalogClient = buildClient(webClientBuilder, connector, upstream.getCatalogUrl());
        this.searchClient = buildClient(webClientBuilder, connector, upstream.getSearchUrl());
        this.featureFlagClient = buildClient(webClientBuilder, connector, upstream.getFeatureFlagUrl());
        this.cartClient = buildClient(webClientBuilder, connector, upstream.getCartUrl());
        this.orderClient = buildClient(webClientBuilder, connector, upstream.getOrderUrl());
        this.identityClient = buildClient(webClientBuilder, connector, upstream.getIdentityUrl());
        this.walletLoyaltyClient = buildClient(webClientBuilder, connector, upstream.getWalletUrl());
        this.pricingClient = buildClient(webClientBuilder, connector, upstream.getPricingUrl());
        this.inventoryClient = buildClient(webClientBuilder, connector, upstream.getInventoryUrl());
        this.fulfillmentClient = buildClient(webClientBuilder, connector, upstream.getFulfillmentUrl());
        this.routingEtaClient = buildClient(webClientBuilder, connector, upstream.getRoutingEtaUrl());
        this.checkoutClient = buildClient(webClientBuilder, connector, upstream.getCheckoutUrl());
    }

    private WebClient buildClient(
            WebClient.Builder webClientBuilder,
            ReactorClientHttpConnector connector,
            String baseUrl) {
        return webClientBuilder.clone()
            .baseUrl(baseUrl)
            .clientConnector(connector)
            .defaultHeaders(this::applyInternalHeaders)
            .build();
    }

    public void applyInternalHeaders(HttpHeaders headers) {
        if (internalServiceName != null && !internalServiceName.isBlank()) {
            headers.set(HEADER_INTERNAL_SERVICE, internalServiceName);
        }
        if (internalServiceToken != null && !internalServiceToken.isBlank()) {
            headers.set(HEADER_INTERNAL_TOKEN, internalServiceToken);
        }
    }

    public WebClient catalogClient() {
        return catalogClient;
    }

    public WebClient searchClient() {
        return searchClient;
    }

    public WebClient featureFlagClient() {
        return featureFlagClient;
    }

    public WebClient cartClient() {
        return cartClient;
    }

    public WebClient orderClient() {
        return orderClient;
    }

    public WebClient identityClient() {
        return identityClient;
    }

    public WebClient walletLoyaltyClient() {
        return walletLoyaltyClient;
    }

    public WebClient pricingClient() {
        return pricingClient;
    }

    public WebClient inventoryClient() {
        return inventoryClient;
    }

    public WebClient fulfillmentClient() {
        return fulfillmentClient;
    }

    public WebClient routingEtaClient() {
        return routingEtaClient;
    }

    public WebClient checkoutClient() {
        return checkoutClient;
    }
}
