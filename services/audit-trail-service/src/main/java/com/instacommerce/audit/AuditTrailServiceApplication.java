package com.instacommerce.audit;

import com.instacommerce.audit.config.AuditProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AuditProperties.class)
public class AuditTrailServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuditTrailServiceApplication.class, args);
    }
}
