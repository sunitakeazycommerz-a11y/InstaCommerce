package com.instacommerce.routing;

import com.instacommerce.routing.config.RoutingProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableConfigurationProperties({RoutingProperties.class})
@EnableCaching
public class RoutingEtaServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(RoutingEtaServiceApplication.class, args);
    }
}
