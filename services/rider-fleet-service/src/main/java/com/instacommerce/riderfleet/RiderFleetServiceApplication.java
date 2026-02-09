package com.instacommerce.riderfleet;

import com.instacommerce.riderfleet.config.RiderFleetProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(RiderFleetProperties.class)
public class RiderFleetServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(RiderFleetServiceApplication.class, args);
    }
}
