package com.instacommerce.payment.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates internal service-to-service requests using shared token headers.
 * Defense-in-depth on top of Istio mTLS.
 */
@Component
public class InternalServiceAuthFilter extends OncePerRequestFilter {
    private final String expectedToken;

    public InternalServiceAuthFilter(
            @Value("${internal.service.token:dev-internal-token-change-in-prod}") String expectedToken) {
        this.expectedToken = expectedToken;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String serviceName = request.getHeader("X-Internal-Service");
        String token = request.getHeader("X-Internal-Token");
        if (serviceName != null && token != null && token.equals(expectedToken)) {
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    serviceName, null,
                    List.of(new SimpleGrantedAuthority("ROLE_INTERNAL_SERVICE"),
                            new SimpleGrantedAuthority("ROLE_ADMIN")));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        filterChain.doFilter(request, response);
    }
}
