package com.instacommerce.notification.security;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolves the client IP address using right-to-left X-Forwarded-For traversal across trusted proxies.
 */
@Component
public class ClientIpResolver {
    private static final List<String> DEFAULT_TRUSTED_PROXIES = List.of(
        "127.0.0.0/8",
        "10.0.0.0/8",
        "172.16.0.0/12",
        "192.168.0.0/16",
        "::1/128",
        "fc00::/7",
        "fe80::/10"
    );

    private static final Pattern IPV4_PATTERN = Pattern.compile(
        "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$"
    );
    private static final Pattern IPV6_CHARS_PATTERN = Pattern.compile("^[0-9a-fA-F:.]+$");

    private final List<CidrRange> trustedRanges;

    public ClientIpResolver() {
        this(DEFAULT_TRUSTED_PROXIES);
    }

    @Autowired
    public ClientIpResolver(
            @Value("${notification.security.trusted-proxies:127.0.0.0/8,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,::1/128,fc00::/7,fe80::/10}")
            List<String> trustedProxies) {
        List<String> configured = (trustedProxies == null || trustedProxies.isEmpty())
            ? DEFAULT_TRUSTED_PROXIES
            : trustedProxies;
        List<CidrRange> ranges = new ArrayList<>(configured.size());
        for (String entry : configured) {
            if (entry != null && !entry.isBlank()) {
                ranges.add(CidrRange.parse(entry.trim()));
            }
        }
        this.trustedRanges = List.copyOf(ranges);
    }

    public String resolve(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String remoteAddr = normalizeIp(request.getRemoteAddr());
        if (remoteAddr == null) {
            return null;
        }
        InetAddress remoteInet = parseLiteral(remoteAddr);
        if (remoteInet == null || !isTrustedProxy(remoteInet)) {
            return remoteAddr;
        }
        List<String> hops = collectForwardedHops(request);
        if (hops.isEmpty()) {
            return remoteAddr;
        }
        String lastTrustedHop = null;
        for (int i = hops.size() - 1; i >= 0; i--) {
            String hop = hops.get(i);
            InetAddress hopInet = parseLiteral(hop);
            if (hopInet == null) {
                return remoteAddr;
            }
            if (!isTrustedProxy(hopInet)) {
                return hop;
            }
            lastTrustedHop = hop;
        }
        return lastTrustedHop != null ? lastTrustedHop : remoteAddr;
    }

    private List<String> collectForwardedHops(HttpServletRequest request) {
        List<String> headerValues = new ArrayList<>();
        Enumeration<String> headers = request.getHeaders("X-Forwarded-For");
        if (headers != null) {
            while (headers.hasMoreElements()) {
                String value = headers.nextElement();
                if (value != null && !value.isBlank()) {
                    headerValues.add(value);
                }
            }
        }
        if (headerValues.isEmpty()) {
            String single = request.getHeader("X-Forwarded-For");
            if (single != null && !single.isBlank()) {
                headerValues.add(single);
            }
        }
        List<String> hops = new ArrayList<>();
        for (String header : headerValues) {
            for (String part : header.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    hops.add(trimmed);
                }
            }
        }
        return hops;
    }

    private boolean isTrustedProxy(InetAddress address) {
        for (CidrRange range : trustedRanges) {
            if (range.matches(address)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeIp(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        int zoneIdx = trimmed.indexOf('%');
        if (zoneIdx > 0 && trimmed.contains(":")) {
            trimmed = trimmed.substring(0, zoneIdx);
        }
        return trimmed;
    }

    private static InetAddress parseLiteral(String candidate) {
        if (candidate == null || candidate.isBlank() || candidate.length() > 45) {
            return null;
        }
        String normalized = normalizeIp(candidate);
        if (normalized == null) {
            return null;
        }
        boolean validSyntax = IPV4_PATTERN.matcher(normalized).matches()
            || (normalized.contains(":") && IPV6_CHARS_PATTERN.matcher(normalized).matches());
        if (!validSyntax) {
            return null;
        }
        try {
            return InetAddress.getByName(normalized);
        } catch (UnknownHostException ex) {
            return null;
        }
    }

    private record CidrRange(byte[] network, int prefixLength) {
        static CidrRange parse(String cidr) {
            String[] parts = cidr.split("/", 2);
            InetAddress addr = parseLiteral(parts[0].trim());
            if (addr == null) {
                throw new IllegalArgumentException("Invalid trusted proxy CIDR: " + cidr);
            }
            byte[] bytes = addr.getAddress();
            int maxBits = bytes.length * 8;
            int prefix = parts.length == 2 ? Integer.parseInt(parts[1].trim()) : maxBits;
            if (prefix < 0 || prefix > maxBits) {
                throw new IllegalArgumentException("Invalid CIDR prefix length: " + cidr);
            }
            return new CidrRange(bytes, prefix);
        }

        boolean matches(InetAddress candidate) {
            byte[] addr = candidate.getAddress();
            if (addr.length != network.length) {
                return false;
            }
            int fullBytes = prefixLength / 8;
            int remainingBits = prefixLength % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (addr[i] != network[i]) {
                    return false;
                }
            }
            if (remainingBits > 0) {
                int mask = (0xFF << (8 - remainingBits)) & 0xFF;
                return (addr[fullBytes] & mask) == (network[fullBytes] & mask);
            }
            return true;
        }
    }
}
