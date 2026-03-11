package com.instacommerce.payment;

import com.instacommerce.payment.config.PaymentProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication(excludeName = {
    "org.springframework.cloud.autoconfigure.ConfigurationPropertiesRebinderAutoConfiguration",
    "org.springframework.cloud.autoconfigure.LifecycleMvcEndpointAutoConfiguration",
    "org.springframework.cloud.autoconfigure.RefreshAutoConfiguration",
    "org.springframework.cloud.autoconfigure.RefreshEndpointAutoConfiguration",
    "org.springframework.cloud.autoconfigure.WritableEnvironmentEndpointAutoConfiguration"
})
@EnableConfigurationProperties(PaymentProperties.class)
public class PaymentServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}
