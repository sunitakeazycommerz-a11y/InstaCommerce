package com.instacommerce.checkout.config;

import com.instacommerce.checkout.security.InternalServiceAuthInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
public class RestClientConfig {

    @Value("${internal.service.name:${spring.application.name}}")
    private String serviceName;

    @Value("${internal.service.token:dev-internal-token-change-in-prod}")
    private String serviceToken;

    @Bean("cartRestTemplate")
    public RestTemplate cartRestTemplate(RestTemplateBuilder builder, CheckoutProperties props) {
        return buildRestTemplate(builder, props.getClients().getCart());
    }

    @Bean("pricingRestTemplate")
    public RestTemplate pricingRestTemplate(RestTemplateBuilder builder, CheckoutProperties props) {
        return buildRestTemplate(builder, props.getClients().getPricing());
    }

    @Bean("inventoryRestTemplate")
    public RestTemplate inventoryRestTemplate(RestTemplateBuilder builder, CheckoutProperties props) {
        return buildRestTemplate(builder, props.getClients().getInventory());
    }

    @Bean("paymentRestTemplate")
    public RestTemplate paymentRestTemplate(RestTemplateBuilder builder, CheckoutProperties props) {
        return buildRestTemplate(builder, props.getClients().getPayment());
    }

    @Bean("orderRestTemplate")
    public RestTemplate orderRestTemplate(RestTemplateBuilder builder, CheckoutProperties props) {
        return buildRestTemplate(builder, props.getClients().getOrder());
    }

    private RestTemplate buildRestTemplate(RestTemplateBuilder builder, CheckoutProperties.ServiceClient client) {
        return builder
            .rootUri(client.getBaseUrl())
            .connectTimeout(Duration.ofMillis(client.getConnectTimeout()))
            .readTimeout(Duration.ofMillis(client.getReadTimeout()))
            .additionalInterceptors(new InternalServiceAuthInterceptor(serviceName, serviceToken))
            .build();
    }
}

