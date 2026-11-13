package com.instacommerce.inventory.security;

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
            @Value("${inventory.security.trusted-proxies:127.0.0.0/8,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,::1/128,fc00::/7,fe80::/10}")
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
            String[] parts = header.split(",");
            for (String part : parts) {
                String normalized = normalizeIp(part);
                if (normalized == null) {
                    return List.of();
                }
                hops.add(normalized);
            }
        }
        return hops;
    }

    private boolean isTrustedProxy(InetAddress address) {
        for (CidrRange range : trustedRanges) {
            if (range.contains(address)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeIp(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static InetAddress parseLiteral(String literal) {
        if (literal == null) {
            return null;
        }
        boolean isV4 = IPV4_PATTERN.matcher(literal).matches();
        boolean isV6 = literal.indexOf(':') >= 0 && IPV6_CHARS_PATTERN.matcher(literal).matches();
        if (!isV4 && !isV6) {
            return null;
        }
        try {
            return InetAddress.getByName(literal);
        } catch (UnknownHostException ex) {
            return null;
        }
    }

    private record CidrRange(byte[] networkBytes, int prefixLength) {
        static CidrRange parse(String cidr) {
            String[] parts = cidr.split("/", 2);
            InetAddress addr = parseLiteral(parts[0].trim());
            if (addr == null) {
                throw new IllegalArgumentException("Invalid trusted proxy CIDR: " + cidr);
            }
            byte[] bytes = addr.getAddress();
            int maxPrefix = bytes.length * 8;
            int prefix = parts.length == 2 ? Integer.parseInt(parts[1].trim()) : maxPrefix;
            if (prefix < 0 || prefix > maxPrefix) {
                throw new IllegalArgumentException("Invalid trusted proxy prefix in CIDR: " + cidr);
            }
            return new CidrRange(bytes, prefix);
        }

        boolean contains(InetAddress candidate) {
            byte[] candidateBytes = candidate.getAddress();
            if (candidateBytes.length != networkBytes.length) {
                return false;
            }
            int fullBytes = prefixLength / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (candidateBytes[i] != networkBytes[i]) {
                    return false;
                }
            }
            int remainingBits = prefixLength % 8;
            if (remainingBits == 0) {
                return true;
            }
            int mask = 0xFF << (8 - remainingBits);
            return (candidateBytes[fullBytes] & mask) == (networkBytes[fullBytes] & mask);
        }
    }
}
