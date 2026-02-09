package com.instacommerce.inventory;

import com.instacommerce.inventory.config.InventoryProperties;
import com.instacommerce.inventory.config.ReservationProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({InventoryProperties.class, ReservationProperties.class})
public class InventoryServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApplication.class, args);
    }
}
