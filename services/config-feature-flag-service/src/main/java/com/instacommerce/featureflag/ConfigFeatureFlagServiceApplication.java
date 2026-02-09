package com.instacommerce.featureflag;

import com.instacommerce.featureflag.config.FeatureFlagProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties(FeatureFlagProperties.class)
@EnableCaching
@EnableScheduling
public class ConfigFeatureFlagServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConfigFeatureFlagServiceApplication.class, args);
    }
}
