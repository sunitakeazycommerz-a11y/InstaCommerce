package com.instacommerce.search;

import com.instacommerce.search.config.SearchProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(SearchProperties.class)
public class SearchServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(SearchServiceApplication.class, args);
    }
}
