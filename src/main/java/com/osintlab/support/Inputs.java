package com.osintlab.support;

import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Normalises and validates user input before it reaches a tool. Throws
 * {@link IllegalArgumentException}, which the API maps to HTTP 400.
 */
public final class Inputs {

    private static final Pattern DOMAIN =
            Pattern.compile("^(?=.{1,253}$)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z][a-z0-9-]{0,62}$");
    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9_][A-Za-z0-9._-]{0,39}$");
    private static final Pattern IPV4 = Pattern.compile("^((25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1?\\d?\\d)$");
    private static final Pattern IPV6_CHARS = Pattern.compile("^[0-9a-fA-F:.]+$");
    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]{1,64}@[^@\\s]+$");
    private static final Pattern PHONE = Pattern.compile("^\\+?[0-9 ().\\-/]{6,30}$");
    private static final Pattern COMPANY = Pattern.compile("^[\\p{L}\\p{N}][\\p{L}\\p{N} .,&'()-]{1,79}$");

    private Inputs() {
    }

    /** Accepts "example.com", "https://Example.com/path", "*.example.com." and returns "example.com". */
    public static String domain(String raw) {
        String s = require(raw, "domain").toLowerCase(Locale.ROOT);
        s = s.replaceFirst("^[a-z][a-z0-9+.-]*://", "");
        s = s.replaceFirst("^[^/@]*@", ""); // user@ in URLs
        s = s.replaceFirst("[/?#:].*$", "");
        if (s.startsWith("*.")) s = s.substring(2);
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        try {
            s = IDN.toASCII(s);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Not a valid domain name: " + raw);
        }
        if (!DOMAIN.matcher(s).matches()) {
            throw new IllegalArgumentException("Not a valid domain name: " + raw);
        }
        return s;
    }

    public static boolean isIp(String raw) {
        if (raw == null) return false;
        String s = raw.trim();
        if (IPV4.matcher(s).matches()) return true;
        if (s.contains(":") && IPV6_CHARS.matcher(s).matches()) {
            try {
                InetAddress.getByName(s); // literal - no DNS lookup
                return true;
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }

    /**
     * False for addresses that aren't on the public internet: private, loopback, link-local, CGNAT,
     * multicast, documentation and reserved ranges. Only call it with a validated IP literal.
     */
    public static boolean isPublicIp(String ip) {
        if (!isIp(ip)) return false;
        try {
            InetAddress a = InetAddress.getByName(ip.trim()); // literal - no DNS lookup
            if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()
                    || a.isSiteLocalAddress() || a.isMulticastAddress()) {
                return false;
            }
            byte[] b = a.getAddress();
            if (b.length == 16) return (b[0] & 0xfe) != 0xfc; // IPv6 unique local fc00::/7
            int x = b[0] & 0xff, y = b[1] & 0xff, z = b[2] & 0xff;
            if (x == 0 || x >= 240) return false;                              // "this network", reserved
            if (x == 100 && y >= 64 && y <= 127) return false;                 // CGNAT 100.64/10
            if (x == 198 && (y == 18 || y == 19)) return false;                // benchmarking 198.18/15
            return !((x == 192 && y == 0 && z == 2) || (x == 198 && y == 51 && z == 100)
                    || (x == 203 && y == 0 && z == 113));                      // documentation ranges
        } catch (Exception e) {
            return false;
        }
    }

    public static String ip(String raw) {
        String s = require(raw, "IP");
        if (!isIp(s)) throw new IllegalArgumentException("Not a valid IP address: " + raw);
        return s;
    }

    /** Domain or IP literal. */
    public static String host(String raw) {
        String s = require(raw, "host");
        return isIp(s) ? s : domain(s);
    }

    /** Adds https:// when no scheme is given; only http/https are allowed. */
    public static URI url(String raw) {
        String s = require(raw, "URL");
        if (!s.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) s = "https://" + s;
        try {
            URI uri = new URI(s);
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                throw new IllegalArgumentException("Only http and https URLs are supported");
            }
            if (uri.getHost() == null) throw new IllegalArgumentException("URL has no host: " + raw);
            host(uri.getHost());
            return uri;
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Not a valid URL: " + raw);
        }
    }

    /**
     * Accepts "name" or "@name". Restricted to characters usernames use on virtually every site;
     * since it can't start with '-' it can never be mistaken for a CLI option.
     */
    public static String username(String raw) {
        String s = require(raw, "username");
        if (s.startsWith("@")) s = s.substring(1);
        if (!USERNAME.matcher(s).matches()) {
            throw new IllegalArgumentException(
                    "Not a valid username (letters, digits, '.', '_', '-', max 40, can't start with '-' or '.'): " + raw);
        }
        return s;
    }

    /** Returns the lower-cased address; the domain part is validated like {@link #domain}. */
    public static String email(String raw) {
        String s = require(raw, "email").toLowerCase(Locale.ROOT);
        if (!EMAIL.matcher(s).matches() || s.startsWith("-")) {
            throw new IllegalArgumentException("Not a valid email address: " + raw);
        }
        String domainPart = s.substring(s.indexOf('@') + 1);
        return s.substring(0, s.indexOf('@') + 1) + domain(domainPart);
    }

    /** Returns the number with formatting removed, keeping a leading '+'. */
    public static String phone(String raw) {
        String s = require(raw, "phone number");
        if (!PHONE.matcher(s).matches()) throw new IllegalArgumentException("Not a valid phone number: " + raw);
        String digits = s.replaceAll("[^0-9]", "");
        if (digits.length() < 6 || digits.length() > 15) {
            throw new IllegalArgumentException("Not a valid phone number: " + raw);
        }
        return (s.startsWith("+") ? "+" : "") + digits;
    }

    /** Company / organisation name: letters, digits, spaces and . , & ' ( ) - */
    public static String company(String raw) {
        String s = require(raw, "company name").replaceAll("\\s+", " ");
        if (!COMPANY.matcher(s).matches()) {
            throw new IllegalArgumentException("Not a valid company name (letters, digits, spaces, . , & ' ( ) -): " + raw);
        }
        return s;
    }

    public static String require(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Missing " + field);
        }
        return raw.trim();
    }
}
