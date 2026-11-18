package com.instacommerce.platform.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration registering {@link InternalServiceProperties} and the shared
 * {@link SharedInternalServiceAuthFilter} bean.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InternalServiceProperties.class)
public class InternalServiceSecurityConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SharedInternalServiceAuthFilter internalServiceAuthFilter(InternalServiceProperties properties) {
        return new SharedInternalServiceAuthFilter(properties);
    }
}
