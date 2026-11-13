package com.instacommerce.payment.util;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;

public final class RequestContextUtil {

    private static final List<String> DEFAULT_TRUSTED_PROXY_STRINGS = List.of(
        "127.0.0.0/8",
        "10.0.0.0/8",
        "172.16.0.0/12",
        "192.168.0.0/16",
        "169.254.0.0/16",
        "100.64.0.0/10",
        "35.191.0.0/16",
        "130.211.0.0/22",
        "::1/128",
        "fc00::/7",
        "fe80::/10"
    );

    private static final List<CidrRange> DEFAULT_TRUSTED_PROXIES = parseTrustedProxies(DEFAULT_TRUSTED_PROXY_STRINGS);

    private RequestContextUtil() {
    }

    /**
     * Resolves the client IP using default RFC1918, loopback, GCP LB, and ULA trusted proxy CIDRs.
     */
    public static String resolveIp(HttpServletRequest request) {
        return resolveIp(request, DEFAULT_TRUSTED_PROXIES);
    }

    /**
     * Resolves the client IP by walking {@code X-Forwarded-For} right-to-left only when the direct
     * peer {@code remoteAddr} belongs to {@code trustedProxies}, falling back to {@code remoteAddr}.
     */
    public static String resolveIp(HttpServletRequest request, Collection<String> trustedProxies) {
        List<CidrRange> parsed = trustedProxies == null ? List.of() : parseTrustedProxies(trustedProxies);
        return resolveIp(request, parsed);
    }

    public static String resolveUserAgent(HttpServletRequest request) {
        return request == null ? null : request.getHeader("User-Agent");
    }

    private static String resolveIp(HttpServletRequest request, List<CidrRange> trustedProxies) {
        if (request == null) {
            return null;
        }
        String rawRemoteAddr = request.getRemoteAddr();
        String normalizedRemoteAddr = normalizeHop(rawRemoteAddr);
        String fallbackIp = normalizedRemoteAddr != null ? normalizedRemoteAddr : rawRemoteAddr;

        byte[] remoteBytes = parseIpLiteral(normalizedRemoteAddr);
        if (remoteBytes == null || !isTrusted(remoteBytes, trustedProxies)) {
            return fallbackIp;
        }

        List<String> hops = extractForwardedHops(request);
        if (hops.isEmpty()) {
            return fallbackIp;
        }

        String leftmostTrustedHop = null;
        for (int i = hops.size() - 1; i >= 0; i--) {
            String candidate = normalizeHop(hops.get(i));
            byte[] candidateBytes = parseIpLiteral(candidate);
            if (candidateBytes == null) {
                return fallbackIp;
            }
            if (!isTrusted(candidateBytes, trustedProxies)) {
                return candidate;
            }
            leftmostTrustedHop = candidate;
        }
        return leftmostTrustedHop != null ? leftmostTrustedHop : fallbackIp;
    }

    private static List<String> extractForwardedHops(HttpServletRequest request) {
        List<String> rawHeaders = new ArrayList<>();
        Enumeration<String> headerValues = request.getHeaders("X-Forwarded-For");
        if (headerValues != null && headerValues.hasMoreElements()) {
            while (headerValues.hasMoreElements()) {
                String value = headerValues.nextElement();
                if (value != null && !value.isBlank()) {
                    rawHeaders.add(value);
                }
            }
        } else {
            String single = request.getHeader("X-Forwarded-For");
            if (single != null && !single.isBlank()) {
                rawHeaders.add(single);
            }
        }

        List<String> hops = new ArrayList<>();
        for (String header : rawHeaders) {
            String[] parts = header.split(",");
            for (String part : parts) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    hops.add(trimmed);
                }
            }
        }
        return hops;
    }

    private static String normalizeHop(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1).trim();
        }
        if (value.startsWith("[")) {
            int closeBracket = value.indexOf(']');
            if (closeBracket <= 1) {
                return null;
            }
            String afterBracket = value.substring(closeBracket + 1);
            if (!afterBracket.isEmpty() && !isValidPortSuffix(afterBracket)) {
                return null;
            }
            return stripZoneId(value.substring(1, closeBracket));
        }

        int firstColon = value.indexOf(':');
        int lastColon = value.lastIndexOf(':');
        if (firstColon > 0 && firstColon == lastColon) {
            String host = value.substring(0, firstColon);
            String port = value.substring(firstColon);
            if (!isValidPortSuffix(port)) {
                return null;
            }
            return host;
        }
        return stripZoneId(value);
    }

    private static String stripZoneId(String value) {
        int percent = value.indexOf('%');
        if (percent == 0) {
            return null;
        }
        return percent > 0 ? value.substring(0, percent) : value;
    }

    private static boolean isValidPortSuffix(String suffix) {
        if (!suffix.startsWith(":") || suffix.length() < 2 || suffix.length() > 6) {
            return false;
        }
        for (int i = 1; i < suffix.length(); i++) {
            char c = suffix.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        int port = Integer.parseInt(suffix.substring(1));
        return port >= 0 && port <= 65535;
    }

    private static List<CidrRange> parseTrustedProxies(Collection<String> cidrs) {
        List<CidrRange> ranges = new ArrayList<>();
        for (String cidr : cidrs) {
            if (cidr != null && !cidr.isBlank()) {
                ranges.add(CidrRange.parse(cidr.trim()));
            }
        }
        return List.copyOf(ranges);
    }

    private static boolean isTrusted(byte[] candidate, List<CidrRange> trustedProxies) {
        for (CidrRange range : trustedProxies) {
            if (range.matches(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static byte[] parseIpLiteral(String literal) {
        if (literal == null || literal.isEmpty()) {
            return null;
        }
        if (literal.indexOf(':') >= 0) {
            return parseIpv6(literal);
        }
        return parseIpv4(literal);
    }

    private static byte[] parseIpv4(String literal) {
        String[] parts = literal.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            String part = parts[i];
            if (part.isEmpty() || part.length() > 3) {
                return null;
            }
            if (part.length() > 1 && part.charAt(0) == '0') {
                return null;
            }
            int value = 0;
            for (int j = 0; j < part.length(); j++) {
                char c = part.charAt(j);
                if (c < '0' || c > '9') {
                    return null;
                }
                value = value * 10 + (c - '0');
            }
            if (value > 255) {
                return null;
            }
            bytes[i] = (byte) value;
        }
        return bytes;
    }

    private static byte[] parseIpv6(String literal) {
        if (literal.isEmpty() || literal.indexOf(":::") >= 0) {
            return null;
        }
        int doubleColonIdx = literal.indexOf("::");
        if (doubleColonIdx >= 0 && literal.indexOf("::", doubleColonIdx + 2) >= 0) {
            return null;
        }

        String leftPart;
        String rightPart;
        if (doubleColonIdx >= 0) {
            leftPart = literal.substring(0, doubleColonIdx);
            rightPart = literal.substring(doubleColonIdx + 2);
        } else {
            leftPart = literal;
            rightPart = null;
        }

        List<Integer> leftGroups = new ArrayList<>();
        if (!parseIpv6Side(leftPart, doubleColonIdx >= 0, leftGroups)) {
            return null;
        }

        List<Integer> rightGroups = new ArrayList<>();
        if (rightPart != null && !parseIpv6Side(rightPart, true, rightGroups)) {
            return null;
        }

        int totalGroups = leftGroups.size() + rightGroups.size();
        if (doubleColonIdx >= 0) {
            if (totalGroups >= 8) {
                return null;
            }
        } else if (totalGroups != 8) {
            return null;
        }

        byte[] result = new byte[16];
        int pos = 0;
        for (int group : leftGroups) {
            result[pos++] = (byte) ((group >>> 8) & 0xFF);
            result[pos++] = (byte) (group & 0xFF);
        }
        int zeroGroups = doubleColonIdx >= 0 ? 8 - totalGroups : 0;
        pos += zeroGroups * 2;
        for (int group : rightGroups) {
            result[pos++] = (byte) ((group >>> 8) & 0xFF);
            result[pos++] = (byte) (group & 0xFF);
        }
        return result;
    }

    private static boolean parseIpv6Side(String segment, boolean allowEmpty, List<Integer> target) {
        if (segment.isEmpty()) {
            return allowEmpty;
        }
        if (segment.startsWith(":") || segment.endsWith(":")) {
            return false;
        }
        String[] tokens = segment.split(":", -1);
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i];
            if (token.isEmpty()) {
                return false;
            }
            if (token.indexOf('.') >= 0) {
                if (i != tokens.length - 1) {
                    return false;
                }
                byte[] ipv4 = parseIpv4(token);
                if (ipv4 == null) {
                    return false;
                }
                target.add(((ipv4[0] & 0xFF) << 8) | (ipv4[1] & 0xFF));
                target.add(((ipv4[2] & 0xFF) << 8) | (ipv4[3] & 0xFF));
            } else {
                if (token.length() > 4) {
                    return false;
                }
                int value = 0;
                for (int j = 0; j < token.length(); j++) {
                    int digit = Character.digit(token.charAt(j), 16);
                    if (digit < 0) {
                        return false;
                    }
                    value = (value << 4) | digit;
                }
                target.add(value);
            }
        }
        return true;
    }

    private static byte[] unwrapIpv4Mapped(byte[] address) {
        if (address.length != 16) {
            return address;
        }
        for (int i = 0; i < 10; i++) {
            if (address[i] != 0) {
                return address;
            }
        }
        if (address[10] != (byte) 0xFF || address[11] != (byte) 0xFF) {
            return address;
        }
        return Arrays.copyOfRange(address, 12, 16);
    }

    private record CidrRange(byte[] network, int prefixLength) {

        static CidrRange parse(String raw) {
            int slash = raw.indexOf('/');
            String ipPart = slash >= 0 ? raw.substring(0, slash) : raw;
            String normalizedIp = normalizeHop(ipPart);
            byte[] ipBytes = parseIpLiteral(normalizedIp);
            if (ipBytes == null) {
                throw new IllegalArgumentException("Invalid trusted proxy CIDR address: " + raw);
            }
            ipBytes = unwrapIpv4Mapped(ipBytes);
            int maxBits = ipBytes.length * 8;
            int prefix = maxBits;
            if (slash >= 0) {
                String prefixPart = raw.substring(slash + 1);
                if (prefixPart.isEmpty()) {
                    throw new IllegalArgumentException("Missing CIDR prefix length: " + raw);
                }
                try {
                    prefix = Integer.parseInt(prefixPart);
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException("Invalid CIDR prefix length: " + raw, ex);
                }
                if (prefix < 0 || prefix > maxBits) {
                    throw new IllegalArgumentException("CIDR prefix out of range: " + raw);
                }
            }
            return new CidrRange(ipBytes, prefix);
        }

        boolean matches(byte[] candidate) {
            byte[] effectiveCandidate = network.length == 4 ? unwrapIpv4Mapped(candidate) : candidate;
            if (effectiveCandidate.length != network.length) {
                return false;
            }
            int fullBytes = prefixLength / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (effectiveCandidate[i] != network[i]) {
                    return false;
                }
            }
            int remainingBits = prefixLength % 8;
            if (remainingBits == 0) {
                return true;
            }
            int mask = 0xFF << (8 - remainingBits);
            return (effectiveCandidate[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }
}
