package com.instacommerce.catalog.security;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolves the originating client IP by walking X-Forwarded-For from right to left across trusted proxies.
 */
@Component
public class ClientIpResolver {

    private static final String DEFAULT_TRUSTED_PROXY_CIDRS =
        "127.0.0.1/32,::1/128,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,100.64.0.0/10,fc00::/7,fe80::/10";

    private final List<CidrBlock> trustedProxies;

    public ClientIpResolver() {
        this(DEFAULT_TRUSTED_PROXY_CIDRS);
    }

    @Autowired
    public ClientIpResolver(
            @Value("${catalog.security.trusted-proxies:" + DEFAULT_TRUSTED_PROXY_CIDRS + "}")
            String trustedProxyCidrs) {
        this.trustedProxies = parseTrustedProxies(trustedProxyCidrs);
    }

    public String resolve(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String rawRemoteAddr = request.getRemoteAddr();
        InetAddress remoteIp = parseIpLiteral(rawRemoteAddr);
        if (remoteIp == null || !isTrustedProxy(remoteIp)) {
            return remoteIp != null ? remoteIp.getHostAddress() : rawRemoteAddr;
        }

        List<String> hops = extractForwardedHops(request);
        if (hops.isEmpty()) {
            return remoteIp.getHostAddress();
        }

        String lastTrustedIp = remoteIp.getHostAddress();
        for (int i = hops.size() - 1; i >= 0; i--) {
            InetAddress hopIp = parseIpLiteral(hops.get(i));
            if (hopIp == null) {
                return lastTrustedIp;
            }
            String normalizedHop = hopIp.getHostAddress();
            if (!isTrustedProxy(hopIp)) {
                return normalizedHop;
            }
            lastTrustedIp = normalizedHop;
        }
        return lastTrustedIp;
    }

    private List<String> extractForwardedHops(HttpServletRequest request) {
        List<String> hops = new ArrayList<>();
        Enumeration<String> headers = request.getHeaders("X-Forwarded-For");
        if (headers != null && headers.hasMoreElements()) {
            while (headers.hasMoreElements()) {
                addCommaSeparatedHops(headers.nextElement(), hops);
            }
        } else {
            addCommaSeparatedHops(request.getHeader("X-Forwarded-For"), hops);
        }
        return hops;
    }

    private void addCommaSeparatedHops(String headerValue, List<String> hops) {
        if (headerValue == null || headerValue.isBlank()) {
            return;
        }
        for (String part : headerValue.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                hops.add(trimmed);
            }
        }
    }

    private boolean isTrustedProxy(InetAddress address) {
        for (CidrBlock cidr : trustedProxies) {
            if (cidr.matches(address)) {
                return true;
            }
        }
        return false;
    }

    private static List<CidrBlock> parseTrustedProxies(String cidrs) {
        if (cidrs == null || cidrs.isBlank()) {
            return List.of();
        }
        List<CidrBlock> blocks = new ArrayList<>();
        for (String entry : cidrs.split(",")) {
            String trimmed = entry.trim();
            if (!trimmed.isEmpty()) {
                CidrBlock block = CidrBlock.parse(trimmed);
                if (block != null) {
                    blocks.add(block);
                }
            }
        }
        return List.copyOf(blocks);
    }

    private static InetAddress parseIpLiteral(String raw) {
        String candidate = stripQuotesAndPort(raw);
        if (candidate == null || candidate.isEmpty()) {
            return null;
        }
        if (!isStrictIpv4Literal(candidate) && !isStrictIpv6Literal(candidate)) {
            return null;
        }
        try {
            return InetAddress.getByName(candidate);
        } catch (UnknownHostException ex) {
            return null;
        }
    }

    private static String stripQuotesAndPort(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1).trim();
        }
        if (s.isEmpty()) {
            return null;
        }
        if (s.startsWith("[")) {
            int closeBracket = s.indexOf(']');
            if (closeBracket <= 1) {
                return null;
            }
            String remainder = s.substring(closeBracket + 1);
            if (!remainder.isEmpty() && (!remainder.startsWith(":") || !isAllDigits(remainder.substring(1)))) {
                return null;
            }
            return s.substring(1, closeBracket);
        }
        int colonIdx = s.indexOf(':');
        if (colonIdx > 0 && s.indexOf(':', colonIdx + 1) < 0 && s.indexOf('.') > 0) {
            String port = s.substring(colonIdx + 1);
            if (!isAllDigits(port)) {
                return null;
            }
            return s.substring(0, colonIdx);
        }
        return s;
    }

    private static boolean isStrictIpv4Literal(String s) {
        String[] parts = s.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3 || !isAllDigits(part)) {
                return false;
            }
            if (part.length() > 1 && part.charAt(0) == '0') {
                return false;
            }
            int octet = Integer.parseInt(part);
            if (octet < 0 || octet > 255) {
                return false;
            }
        }
        return true;
    }

    private static boolean isStrictIpv6Literal(String s) {
        if (!s.contains(":")) {
            return false;
        }
        int doubleColonIdx = s.indexOf("::");
        if (doubleColonIdx >= 0 && s.indexOf("::", doubleColonIdx + 2) >= 0) {
            return false;
        }
        String[] halves = s.split("::", -1);
        int maxAllowedGroups = doubleColonIdx >= 0 ? 7 : 8;
        int totalGroups = 0;
        for (int h = 0; h < halves.length; h++) {
            String half = halves[h];
            if (half.isEmpty()) {
                continue;
            }
            if (half.startsWith(":") || half.endsWith(":")) {
                return false;
            }
            String[] groups = half.split(":", -1);
            for (int g = 0; g < groups.length; g++) {
                String group = groups[g];
                if (group.contains(".")) {
                    if (h != halves.length - 1 || g != groups.length - 1 || !isStrictIpv4Literal(group)) {
                        return false;
                    }
                    totalGroups += 2;
                } else {
                    if (group.isEmpty() || group.length() > 4 || !isHexString(group)) {
                        return false;
                    }
                    totalGroups++;
                }
            }
        }
        return doubleColonIdx >= 0 ? totalGroups <= maxAllowedGroups : totalGroups == 8;
    }

    private static boolean isAllDigits(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch < '0' || ch > '9') {
                return false;
            }
        }
        return true;
    }

    private static boolean isHexString(String s) {
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            boolean hex = (ch >= '0' && ch <= '9')
                || (ch >= 'a' && ch <= 'f')
                || (ch >= 'A' && ch <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    private record CidrBlock(byte[] networkBytes, int prefixLength) {

        static CidrBlock parse(String cidr) {
            int slashIdx = cidr.indexOf('/');
            String ipPart = slashIdx >= 0 ? cidr.substring(0, slashIdx) : cidr;
            InetAddress address = parseIpLiteral(ipPart);
            if (address == null) {
                return null;
            }
            byte[] bytes = address.getAddress();
            int maxBits = bytes.length * 8;
            int prefix = maxBits;
            if (slashIdx >= 0) {
                String prefixPart = cidr.substring(slashIdx + 1);
                if (!isAllDigits(prefixPart)) {
                    return null;
                }
                prefix = Integer.parseInt(prefixPart);
                if (prefix < 0 || prefix > maxBits) {
                    return null;
                }
            }
            return new CidrBlock(bytes, prefix);
        }

        boolean matches(InetAddress candidate) {
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
            if (remainingBits > 0) {
                int mask = (0xFF << (8 - remainingBits)) & 0xFF;
                return (candidateBytes[fullBytes] & mask) == (networkBytes[fullBytes] & mask);
            }
            return true;
        }
    }
}
