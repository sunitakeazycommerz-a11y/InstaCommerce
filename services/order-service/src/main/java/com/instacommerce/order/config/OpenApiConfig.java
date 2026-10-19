package com.instacommerce.order.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI orderOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("InstaCommerce Order Service API")
                        .version("v1.0.0")
                        .description("OpenAPI 3.1 contract definition for Order management and checkout fulfillment")
                        .contact(new Contact().name("InstaCommerce Order Eng").email("order-eng@instacommerce.com")));
    }
}
