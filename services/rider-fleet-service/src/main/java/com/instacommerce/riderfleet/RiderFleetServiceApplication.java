package com.instacommerce.riderfleet;

import com.instacommerce.riderfleet.config.RiderFleetProperties;
import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableConfigurationProperties(RiderFleetProperties.class)
public class RiderFleetServiceApplication {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    public static void main(String[] args) {
        SpringApplication.run(RiderFleetServiceApplication.class, args);
    }
}
