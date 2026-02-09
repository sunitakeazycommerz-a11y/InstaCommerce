package com.instacommerce.fraud;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class FraudDetectionServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(FraudDetectionServiceApplication.class, args);
    }
}
