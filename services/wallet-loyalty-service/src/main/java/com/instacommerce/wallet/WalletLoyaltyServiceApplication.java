package com.instacommerce.wallet;

import com.instacommerce.wallet.config.WalletProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(WalletProperties.class)
public class WalletLoyaltyServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(WalletLoyaltyServiceApplication.class, args);
    }
}
