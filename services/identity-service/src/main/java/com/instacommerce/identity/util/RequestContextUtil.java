package com.instacommerce.identity.util;

import jakarta.servlet.http.HttpServletRequest;

public final class RequestContextUtil {

    private RequestContextUtil() {
    }

    public static String resolveIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    public static String resolveUserAgent(HttpServletRequest request) {
        return request == null ? null : request.getHeader("User-Agent");
    }
}
