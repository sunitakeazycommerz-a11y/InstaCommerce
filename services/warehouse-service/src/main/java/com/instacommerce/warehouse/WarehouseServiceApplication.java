package com.instacommerce.warehouse;

import com.instacommerce.warehouse.config.WarehouseProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({WarehouseProperties.class})
public class WarehouseServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(WarehouseServiceApplication.class, args);
    }
}
