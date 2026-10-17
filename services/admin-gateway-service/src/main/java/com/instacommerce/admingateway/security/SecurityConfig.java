package com.instacommerce.admingateway.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   AdminJwtAuthenticationFilter jwtAuthenticationFilter,
                                                   AdminSecurityAuditFilter auditFilter,
                                                   RestAuthenticationEntryPoint authenticationEntryPoint,
                                                   RestAccessDeniedHandler accessDeniedHandler) throws Exception {
        http.csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/**", "/admin/health", "/admin/metrics", "/error").permitAll()
                .requestMatchers(HttpMethod.POST, "/admin/v1/**").hasRole("SUPER_ADMIN")
                .requestMatchers(HttpMethod.PUT, "/admin/v1/**").hasRole("SUPER_ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/admin/v1/**").hasRole("SUPER_ADMIN")
                .requestMatchers(HttpMethod.PATCH, "/admin/v1/**").hasRole("SUPER_ADMIN")
                .requestMatchers(HttpMethod.GET, "/admin/v1/**").hasAnyRole("ADMIN", "SUPER_ADMIN")
                .anyRequest().authenticated())
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(authenticationEntryPoint)
                .accessDeniedHandler(accessDeniedHandler))
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(auditFilter, AdminJwtAuthenticationFilter.class);
        return http.build();
    }
}
