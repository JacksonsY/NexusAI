package com.nexusai.common.util;

import java.util.List;

/**
 * IP / CIDR 匹配纯算法（零 Spring/零 IO）：精确 IP 与 IPv4 CIDR 前缀匹配（IPv6 仅精确），
 * 供黑白名单决策（{@code IpSecurityService}）与条目校验（admin 入参）复用。
 *
 * @author nexusai
 */
public final class IpMatcher {

    private IpMatcher() {
    }

    /** ip 命中白名单任一条目（精确 IP 或 IPv4 CIDR）。 */
    public static boolean inWhitelist(String ip, List<String> whitelist) {
        if (ip == null || ip.isBlank() || whitelist == null || whitelist.isEmpty()) {
            return false;
        }
        for (String entry : whitelist) {
            if (entry != null && !entry.isBlank() && matches(ip, entry)) {
                return true;
            }
        }
        return false;
    }

    /** entry 支持精确 IP 与 IPv4 CIDR（IPv6 仅精确匹配）。 */
    public static boolean matches(String ip, String entry) {
        if (ip == null || entry == null || ip.isBlank() || entry.isBlank()) {
            return false;
        }
        if (ip.equals(entry)) {
            return true;
        }
        int slash = entry.indexOf('/');
        if (slash < 0 || ip.contains(":")) {
            return false;
        }
        try {
            long net = ipv4ToLong(entry.substring(0, slash));
            int prefix = Integer.parseInt(entry.substring(slash + 1));
            if (prefix < 0 || prefix > 32) {
                return false;
            }
            long mask = prefix == 0 ? 0 : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
            return (ipv4ToLong(ip) & mask) == (net & mask);
        } catch (IllegalArgumentException e) {
            // 非法 CIDR/IP 字面量（ipv4ToLong / Integer.parseInt 抛出）→ fail-closed 不匹配，符合白名单语义
            return false;
        }
    }

    /** 条目格式校验：IPv4（可带 /0-32 CIDR）或 IPv6 精确 IP。 */
    public static boolean isValidEntry(String entry) {
        if (entry == null || entry.isBlank()) {
            return false;
        }
        String ipPart = entry;
        int slash = entry.indexOf('/');
        if (slash >= 0) {
            ipPart = entry.substring(0, slash);
            try {
                int prefix = Integer.parseInt(entry.substring(slash + 1));
                if (prefix < 0 || prefix > 32) {
                    return false;
                }
            } catch (NumberFormatException e) {
                return false;
            }
        }
        if (ipPart.contains(":")) {
            // IPv6 仅允许精确 IP
            return slash < 0 && isIpv6Literal(ipPart);
        }
        try {
            ipv4ToLong(ipPart);
            return true;
        } catch (IllegalArgumentException e) {
            // 非法 IPv4 字面量 → 条目无效
            return false;
        }
    }

    /** 仅判断 IP 字面量，不做 DNS 解析；用于热路径过滤 XFF hop。 */
    public static boolean isIpLiteral(String ip) {
        if (ip == null || ip.isBlank()) {
            return false;
        }
        if (ip.indexOf(':') >= 0) {
            return isIpv6Literal(ip);
        }
        try {
            ipv4ToLong(ip);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static long ipv4ToLong(String ip) {
        if (ip == null || ip.isBlank()) {
            throw new IllegalArgumentException("非 IPv4: " + ip);
        }
        long value = 0;
        int octet = 0;
        int dots = 0;
        int digits = 0;
        for (int i = 0; i < ip.length(); i++) {
            char ch = ip.charAt(i);
            if (ch == '.') {
                if (digits == 0 || dots == 3) {
                    throw new IllegalArgumentException("非 IPv4: " + ip);
                }
                value = (value << 8) | octet;
                octet = 0;
                digits = 0;
                dots++;
                continue;
            }
            if (ch < '0' || ch > '9') {
                throw new IllegalArgumentException("非 IPv4: " + ip);
            }
            octet = octet * 10 + ch - '0';
            digits++;
            if (digits > 3 || octet > 255) {
                throw new IllegalArgumentException("非 IPv4: " + ip);
            }
        }
        if (dots != 3 || digits == 0) {
            throw new IllegalArgumentException("非 IPv4: " + ip);
        }
        return (value << 8) | octet;
    }

    private static boolean isIpv6Literal(String ip) {
        if (ip == null || ip.isBlank() || ip.indexOf('%') >= 0 || ip.indexOf('[') >= 0 || ip.indexOf(']') >= 0) {
            return false;
        }
        int doubleColon = ip.indexOf("::");
        if (doubleColon != ip.lastIndexOf("::")) {
            return false;
        }
        if (doubleColon >= 0) {
            int before = countIpv6Groups(ip, 0, doubleColon);
            int after = countIpv6Groups(ip, doubleColon + 2, ip.length());
            return before >= 0 && after >= 0 && before + after < 8;
        }
        return countIpv6Groups(ip, 0, ip.length()) == 8;
    }

    private static int countIpv6Groups(String ip, int from, int to) {
        if (from == to) {
            return 0;
        }
        int groups = 0;
        int start = from;
        while (start <= to) {
            int colon = indexOf(ip, ':', start, to);
            int end = colon < 0 ? to : colon;
            if (end == start) {
                return -1;
            }
            String token = ip.substring(start, end);
            if (token.indexOf('.') >= 0) {
                if (colon >= 0 || !isIpLiteral(token)) {
                    return -1;
                }
                return groups + 2;
            }
            if (!isHextet(token)) {
                return -1;
            }
            groups++;
            if (colon < 0) {
                return groups;
            }
            start = colon + 1;
        }
        return -1;
    }

    private static int indexOf(String value, char target, int from, int to) {
        for (int i = from; i < to; i++) {
            if (value.charAt(i) == target) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isHextet(String token) {
        if (token.isEmpty() || token.length() > 4) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            char ch = token.charAt(i);
            boolean digit = ch >= '0' && ch <= '9';
            boolean lowerHex = ch >= 'a' && ch <= 'f';
            boolean upperHex = ch >= 'A' && ch <= 'F';
            if (!digit && !lowerHex && !upperHex) {
                return false;
            }
        }
        return true;
    }
}
