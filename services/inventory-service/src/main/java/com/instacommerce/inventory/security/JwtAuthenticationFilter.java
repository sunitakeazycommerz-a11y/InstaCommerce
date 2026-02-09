package com.instacommerce.inventory.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.inventory.dto.response.ErrorResponse;
import com.instacommerce.inventory.exception.TraceIdProvider;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtService jwtService;
    private final TraceIdProvider traceIdProvider;
    private final ObjectMapper objectMapper;

    public JwtAuthenticationFilter(JwtService jwtService, TraceIdProvider traceIdProvider, ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.traceIdProvider = traceIdProvider;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator")
            || path.equals("/error");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }
        String token = header.substring(7);
        try {
            Jws<Claims> jws = jwtService.validateAccessToken(token);
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                jws.getPayload().getSubject(),
                token,
                jwtService.extractAuthorities(jws.getPayload()));
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            filterChain.doFilter(request, response);
        } catch (JwtException | IllegalArgumentException ex) {
            SecurityContextHolder.clearContext();
            String traceId = traceIdProvider.resolveTraceId(request);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader("X-Trace-Id", traceId);
            ErrorResponse error = new ErrorResponse(
                "TOKEN_INVALID",
                "Token is invalid",
                traceId,
                Instant.now(),
                List.of());
            objectMapper.writeValue(response.getWriter(), error);
        }
    }
}
