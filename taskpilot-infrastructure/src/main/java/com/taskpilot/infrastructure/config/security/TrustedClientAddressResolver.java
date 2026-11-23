package com.taskpilot.infrastructure.config.security;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Resolves canonical client IPv4/IPv6 addresses without trusting spoofed forwarding headers.
 * When forwarded headers are enabled, walks {@code X-Forwarded-For} right-to-left while stripping
 * only configured trusted proxy CIDRs and validating IP literal syntax without DNS lookups.
 */
@Component
@RequiredArgsConstructor
public class TrustedClientAddressResolver {

    private final TrustedProxyProperties properties;

    public String resolve(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String peerRaw = request.getRemoteAddr();
        InetAddress peerAddr = parseIpLiteral(peerRaw);
        if (peerAddr == null) {
            return "unknown";
        }
        String canonicalPeer = peerAddr.getHostAddress();

        if (!properties.isTrustForwardedHeaders()) {
            return canonicalPeer;
        }
        List<CidrRange> trustedRanges = parseCidrs(properties.getTrustedCidrs());
        if (trustedRanges.isEmpty() || !matchesAny(peerAddr, trustedRanges)) {
            return canonicalPeer;
        }

        String xff = request.getHeader("X-Forwarded-For");
        if (xff == null || xff.isBlank() || xff.length() > Math.max(16, properties.getMaxHeaderLength())) {
            return canonicalPeer;
        }

        String[] parts = xff.split(",");
        if (parts.length == 0 || parts.length > Math.max(1, properties.getMaxHops())) {
            return canonicalPeer;
        }

        List<InetAddress> parsedHops = new ArrayList<>(parts.length);
        for (String part : parts) {
            InetAddress hop = parseIpLiteral(part);
            if (hop == null) {
                return canonicalPeer;
            }
            parsedHops.add(hop);
        }

        // Walk right-to-left stripping trusted proxy hops; first non-trusted hop is the client IP.
        for (int i = parsedHops.size() - 1; i >= 0; i--) {
            InetAddress hop = parsedHops.get(i);
            if (!matchesAny(hop, trustedRanges)) {
                return hop.getHostAddress();
            }
        }
        return parsedHops.getFirst().getHostAddress();
    }

    private static List<CidrRange> parseCidrs(List<String> rawCidrs) {
        if (rawCidrs == null || rawCidrs.isEmpty()) {
            return List.of();
        }
        List<CidrRange> ranges = new ArrayList<>(rawCidrs.size());
        for (String raw : rawCidrs) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            CidrRange parsed = CidrRange.tryParse(raw.trim());
            if (parsed != null) {
                ranges.add(parsed);
            }
        }
        return ranges;
    }

    private static boolean matchesAny(InetAddress addr, List<CidrRange> ranges) {
        for (CidrRange range : ranges) {
            if (range.contains(addr)) {
                return true;
            }
        }
        return false;
    }

    static InetAddress parseIpLiteral(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.length() > 64) {
            return null;
        }
        if (!isLikelyIpLiteral(trimmed)) {
            return null;
        }
        try {
            return InetAddress.getByName(trimmed);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static boolean isLikelyIpLiteral(String value) {
        if (value.indexOf(':') >= 0) {
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                boolean ok = (c >= '0' && c <= '9')
                        || (c >= 'a' && c <= 'f')
                        || (c >= 'A' && c <= 'F')
                        || c == ':' || c == '.';
                if (!ok) {
                    return false;
                }
            }
            return true;
        }
        String[] octets = value.split("\\.", -1);
        if (octets.length != 4) {
            return false;
        }
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > 3) {
                return false;
            }
            for (int i = 0; i < octet.length(); i++) {
                char c = octet.charAt(i);
                if (c < '0' || c > '9') {
                    return false;
                }
            }
            int num = Integer.parseInt(octet);
            if (num < 0 || num > 255) {
                return false;
            }
        }
        return true;
    }

    private record CidrRange(byte[] networkBytes, int prefixLength) {
        static CidrRange tryParse(String cidr) {
            int slash = cidr.indexOf('/');
            String ipPart = slash >= 0 ? cidr.substring(0, slash).trim() : cidr;
            InetAddress base = parseIpLiteral(ipPart);
            if (base == null) {
                return null;
            }
            byte[] bytes = base.getAddress();
            int maxBits = bytes.length * 8;
            int prefix = maxBits;
            if (slash >= 0) {
                try {
                    prefix = Integer.parseInt(cidr.substring(slash + 1).trim());
                } catch (NumberFormatException e) {
                    return null;
                }
                if (prefix < 0 || prefix > maxBits) {
                    return null;
                }
            }
            return new CidrRange(bytes, prefix);
        }

        boolean contains(InetAddress address) {
            byte[] target = address.getAddress();
            if (target.length != networkBytes.length) {
                return false;
            }
            int fullBytes = prefixLength / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (target[i] != networkBytes[i]) {
                    return false;
                }
            }
            int remainingBits = prefixLength % 8;
            if (remainingBits > 0) {
                int mask = (0xFF << (8 - remainingBits)) & 0xFF;
                if ((target[fullBytes] & mask) != (networkBytes[fullBytes] & mask)) {
                    return false;
                }
            }
            return true;
        }
    }
}
