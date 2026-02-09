package com.instacommerce.checkout;

import com.instacommerce.checkout.config.CheckoutProperties;
import com.instacommerce.checkout.config.TemporalProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({CheckoutProperties.class, TemporalProperties.class})
public class CheckoutOrchestratorApplication {
    public static void main(String[] args) {
        SpringApplication.run(CheckoutOrchestratorApplication.class, args);
    }
}
