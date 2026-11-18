package com.instacommerce.admingateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AdminGatewayServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AdminGatewayServiceApplication.class, args);
    }
}
