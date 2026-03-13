package com.instacommerce.pricing;

import com.instacommerce.pricing.config.PricingProperties;
import com.instacommerce.pricing.config.QuoteTokenProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({PricingProperties.class, QuoteTokenProperties.class})
public class PricingServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(PricingServiceApplication.class, args);
    }
}
