package com.instacommerce.checkout.config;

import com.instacommerce.checkout.security.InternalServiceAuthInterceptor;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.DefaultUriBuilderFactory;

@Configuration
public class RestClientConfig {

    @Value("${internal.service.name:${spring.application.name}}")
    private String serviceName;

    @Value("${internal.service.token:dev-internal-token-change-in-prod}")
    private String serviceToken;

    @Bean("cartRestTemplate")
    public RestTemplate cartRestTemplate(CheckoutProperties props) {
        return buildRestTemplate(props.getClients().getCart());
    }

    @Bean("pricingRestTemplate")
    public RestTemplate pricingRestTemplate(CheckoutProperties props) {
        return buildRestTemplate(props.getClients().getPricing());
    }

    @Bean("inventoryRestTemplate")
    public RestTemplate inventoryRestTemplate(CheckoutProperties props) {
        return buildRestTemplate(props.getClients().getInventory());
    }

    @Bean("paymentRestTemplate")
    public RestTemplate paymentRestTemplate(CheckoutProperties props) {
        return buildRestTemplate(props.getClients().getPayment());
    }

    @Bean("orderRestTemplate")
    public RestTemplate orderRestTemplate(CheckoutProperties props) {
        return buildRestTemplate(props.getClients().getOrder());
    }

    private RestTemplate buildRestTemplate(CheckoutProperties.ServiceClient client) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(client.getConnectTimeout()));
        requestFactory.setReadTimeout(Duration.ofMillis(client.getReadTimeout()));

        RestTemplate restTemplate = new RestTemplate(requestFactory);
        restTemplate.setUriTemplateHandler(new DefaultUriBuilderFactory(client.getBaseUrl()));
        restTemplate.setInterceptors(List.of(new InternalServiceAuthInterceptor(serviceName, serviceToken)));
        return restTemplate;
    }
}
