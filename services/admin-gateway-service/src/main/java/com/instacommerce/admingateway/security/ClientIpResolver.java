package com.instacommerce.admingateway.security;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ClientIpResolver {

    private static final Logger log = LoggerFactory.getLogger(ClientIpResolver.class);
    private static final Pattern IPV4_PATTERN =
            Pattern.compile("^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$");
    private static final Pattern IPV6_LITERAL_CHARS =
            Pattern.compile("^[0-9a-fA-F:.]+$");

    private ClientIpResolver() {
    }

    public static String resolveClientIp(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        String xf = request.getHeader("X-Forwarded-For");
        if (xf != null && !xf.isBlank() && isTrustedProxy(remoteAddr)) {
            String[] hops = xf.split(",");
            for (int i = hops.length - 1; i >= 0; i--) {
                String candidate = hops[i].trim();
                if (candidate.isEmpty()) {
                    continue;
                }
                if (!isValidIp(candidate)) {
                    log.warn("SECURITY_AUDIT_SPOOFED_IP_DETECTED: invalid X-Forwarded-For header '{}', falling back to remoteAddr {}",
                            candidate, remoteAddr);
                    return remoteAddr;
                }
                if (!isTrustedProxy(candidate) || i == 0) {
                    return candidate;
                }
            }
        }
        return remoteAddr;
    }

    static boolean isTrustedProxy(String ip) {
        if (!isValidIp(ip)) {
            return false;
        }
        try {
            InetAddress address = InetAddress.getByName(ip);
            return address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress();
        } catch (UnknownHostException ex) {
            log.debug("Failed to resolve proxy IP address '{}'", ip, ex);
            return false;
        }
    }

    static boolean isValidIp(String ip) {
        if (ip == null || ip.isBlank() || ip.length() > 45) {
            return false;
        }
        if (IPV4_PATTERN.matcher(ip).matches()) {
            return true;
        }
        int firstColon = ip.indexOf(':');
        if (firstColon < 0 || firstColon == ip.lastIndexOf(':') || !IPV6_LITERAL_CHARS.matcher(ip).matches()) {
            return false;
        }
        try {
            return InetAddress.getByName(ip).getAddress().length == 16;
        } catch (UnknownHostException ex) {
            log.debug("Invalid IPv6 literal '{}'", ip, ex);
            return false;
        }
    }
}
