package com.instacommerce.identity.controller;

import com.instacommerce.identity.dto.request.LoginRequest;
import com.instacommerce.identity.dto.request.RefreshRequest;
import com.instacommerce.identity.dto.request.RegisterRequest;
import com.instacommerce.identity.dto.request.RevokeRequest;
import com.instacommerce.identity.dto.response.AuthResponse;
import com.instacommerce.identity.dto.response.RegisterResponse;
import com.instacommerce.identity.exception.TraceIdProvider;
import com.instacommerce.identity.service.AuthService;
import com.instacommerce.identity.util.RequestContextUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {
    private final AuthService authService;
    private final TraceIdProvider traceIdProvider;

    public AuthController(AuthService authService, TraceIdProvider traceIdProvider) {
        this.authService = authService;
        this.traceIdProvider = traceIdProvider;
    }

    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest request,
                                                     HttpServletRequest httpRequest) {
        String traceId = traceIdProvider.resolveTraceId(httpRequest);
        RegisterResponse response = authService.register(request,
            RequestContextUtil.resolveIp(httpRequest),
            RequestContextUtil.resolveUserAgent(httpRequest),
            traceId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        String traceId = traceIdProvider.resolveTraceId(httpRequest);
        return authService.login(request,
            RequestContextUtil.resolveIp(httpRequest),
            RequestContextUtil.resolveUserAgent(httpRequest),
            traceId);
    }

    @PostMapping("/refresh")
    public AuthResponse refresh(@Valid @RequestBody RefreshRequest request, HttpServletRequest httpRequest) {
        String traceId = traceIdProvider.resolveTraceId(httpRequest);
        return authService.refresh(request,
            RequestContextUtil.resolveIp(httpRequest),
            RequestContextUtil.resolveUserAgent(httpRequest),
            traceId);
    }

    @PostMapping("/revoke")
    public ResponseEntity<Void> revoke(@Valid @RequestBody RevokeRequest request, HttpServletRequest httpRequest) {
        String traceId = traceIdProvider.resolveTraceId(httpRequest);
        authService.revoke(request,
            RequestContextUtil.resolveIp(httpRequest),
            RequestContextUtil.resolveUserAgent(httpRequest),
            traceId);
        return ResponseEntity.noContent().build();
    }
}
