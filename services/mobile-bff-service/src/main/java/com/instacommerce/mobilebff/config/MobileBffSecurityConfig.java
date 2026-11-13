package com.instacommerce.mobilebff.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.mobilebff.security.MobileBffAccessDeniedHandler;
import com.instacommerce.mobilebff.security.UserContextPropagationFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Configuration
@EnableWebFluxSecurity
public class MobileBffSecurityConfig {

    @Bean
    public MobileBffAccessDeniedHandler mobileBffAccessDeniedHandler(
            ObjectProvider<ObjectMapper> objectMapperProvider) {
        return new MobileBffAccessDeniedHandler(objectMapperProvider.getIfAvailable(ObjectMapper::new));
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(
            ServerHttpSecurity http,
            UserContextPropagationFilter userContextPropagationFilter,
            MobileBffAccessDeniedHandler accessDeniedHandler) {
        return http
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
            .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
            .authorizeExchange(exchanges -> exchanges
                .pathMatchers("/actuator/**", "/health/**").permitAll()
                .pathMatchers("/bff/mobile/v1/home", "/m/v1/home").permitAll()
                .pathMatchers("/bff/mobile/v1/public/**", "/m/v1/public/**").permitAll()
                .pathMatchers("/bff/mobile/v1/cart/stream/**", "/m/v1/cart/stream/**").authenticated()
                .anyExchange().authenticated()
            )
            .exceptionHandling(exceptions -> exceptions
                .accessDeniedHandler(accessDeniedHandler)
            )
            .addFilterAt(userContextPropagationFilter, SecurityWebFiltersOrder.AUTHENTICATION)
            .build();
    }
}
