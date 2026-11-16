package com.instacommerce.fulfillment;

import com.instacommerce.fulfillment.config.FulfillmentProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
@EnableConfigurationProperties(FulfillmentProperties.class)
public class FulfillmentServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(FulfillmentServiceApplication.class, args);
    }
}
