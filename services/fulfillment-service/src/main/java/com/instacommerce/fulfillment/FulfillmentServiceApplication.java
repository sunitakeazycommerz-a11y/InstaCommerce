package com.instacommerce.fulfillment;

import com.instacommerce.fulfillment.config.FulfillmentProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(FulfillmentProperties.class)
public class FulfillmentServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(FulfillmentServiceApplication.class, args);
    }
}
