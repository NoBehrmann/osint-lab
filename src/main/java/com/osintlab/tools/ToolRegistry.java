package com.osintlab.tools;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.osintlab.tools.TargetType.*;

/**
 * The tool catalog shown in the UI. A tool is "ready" when an {@link OsintTool} bean with the
 * same id exists; everything else is listed as planned.
 */
@Component
public class ToolRegistry {

    private static final boolean ON = true, OFF = false, SLOW = true, FAST = false;

    private static final List<ToolInfo> CATALOG = List.of(
            tool("dns", "DNS Records", "Domain", "A, AAAA, CNAME, MX, NS, TXT, SOA and reverse DNS", ON, FAST, DOMAIN, URL),
            tool("whois", "WHOIS / RDAP", "Domain", "Registration data for domains, IPs and ASNs", ON, FAST, DOMAIN, IP, URL),
            tool("subdomains", "Subdomains (CT)", "Domain", "Certificate Transparency logs via crt.sh, resolved to IPs", ON, SLOW, DOMAIN),
            tool("harvester", "theHarvester", "Domain", "Emails, hosts and IPs from public sources (~1-2 min)", OFF, SLOW, DOMAIN),
            tool("dnstwist", "Lookalike Domains", "Domain", "Registered typosquatting / phishing domains (dnstwist)", OFF, SLOW, DOMAIN),

            tool("tls", "TLS Certificate", "Web", "Certificate chain, validity, SANs, fingerprints", ON, FAST, DOMAIN, URL, IP),
            tool("http", "HTTP Inspector", "Web", "Headers, redirects, security headers, tech, emails on page", ON, FAST, DOMAIN, URL),
            tool("wayback", "Wayback Machine", "Web", "Snapshot timeline and archived URLs", ON, FAST, DOMAIN, URL),

            tool("ip", "IP Intelligence", "Network", "Geolocation, ISP, ASN, reputation links", ON, FAST, IP, DOMAIN),
            tool("internetdb", "Exposed Services (Shodan)", "Network", "Open ports, software and known CVEs from Shodan's own scans - nothing sent to the target", ON, FAST, IP, DOMAIN),
            tool("network", "Network & Blacklists", "Network", "ASN owner, abuse contact, route validity (RIPEstat) and spam blacklists (DNSBL)", ON, FAST, IP, DOMAIN),

            tool("holehe", "Holehe", "Accounts", "Which of ~120 sites (Instagram, X, Spotify...) an email is registered on", ON, SLOW, EMAIL),
            tool("sherlock", "Sherlock", "Accounts", "Username across 400+ sites (email: its local part)", ON, SLOW, USERNAME, EMAIL),
            tool("maigret", "Maigret", "Accounts", "Username across top 500 of ~3000 sites with profile details (3-6 min)", OFF, SLOW, USERNAME, EMAIL),
            tool("profiles", "Profile Details", "Accounts", "Join dates, bios, linked accounts: Bluesky, Keybase, Reddit, Hacker News, Mastodon, Lichess, Chess.com, Steam, Telegram", ON, FAST, USERNAME),
            tool("code", "Code Platforms", "Accounts", "GitHub, GitLab, Codeberg: profile, orgs, keys, commit emails; email -> GitHub account", ON, FAST, USERNAME, EMAIL),
            tool("breaches", "Data Breaches", "Accounts", "Which data breaches an email or username appears in (names only, no passwords)", ON, FAST, EMAIL, USERNAME),
            tool("social", "Social Media Links", "Accounts", "One-click checks on Facebook, Instagram, X, TikTok, LinkedIn...", ON, FAST, USERNAME, EMAIL, PHONE),
            tool("instagram", "Instagram Profile", "Accounts", "Profile, recent posts, hashtags, locations (needs sessionid)", OFF, FAST, USERNAME),
            tool("toutatis", "Toutatis", "Accounts", "Instagram ID, obfuscated email/phone (needs sessionid)", OFF, FAST, USERNAME),

            tool("email", "Email Analysis", "Email & Phone", "MX, SPF, DMARC, provider, disposable check, Gravatar", ON, FAST, EMAIL),
            tool("phone", "Phone Lookup", "Email & Phone", "Validity, country, region, line type, original carrier", ON, FAST, PHONE),
            tool("phoneinfoga", "PhoneInfoga", "Email & Phone", "Search-engine footprints: social, reputation, disposable-number and people-search sites", ON, FAST, PHONE),

            tool("registry", "Company Registries", "Organisations", "GLEIF (LEI, ownership tree), SEC EDGAR filings, EU VAT check - name, LEI or VAT number", ON, FAST, COMPANY),
            tool("crosslinked", "Employees (CrossLinked)", "Organisations", "LinkedIn employees via CrossLinked + DuckDuckGo", ON, SLOW, COMPANY),

            tool("exif", "File Metadata", "Files", "GPS, device, dates, author, software, file hashes - photos, videos, PDF, Office (ExifTool)", ON, FAST, IMAGE, FILE),
            tool("geolocate", "Photo Geolocation", "Files", "Guesses where a photo was taken from its content (GeoCLIP), no GPS needed", OFF, SLOW, IMAGE),

            tool("dorks", "Dork Generator", "Search", "Search-engine dorks for the target", ON, FAST, DOMAIN, USERNAME, EMAIL, PHONE, COMPANY),
            tool("reverse-image", "Reverse Image", "Search", "Google Lens, Bing, Yandex, TinEye (image URL or uploaded file)", ON, FAST, URL, IMAGE)
    );

    private final Map<String, OsintTool> implementations;

    public ToolRegistry(List<OsintTool> tools) {
        this.implementations = tools.stream().collect(Collectors.toMap(OsintTool::id, Function.identity()));
    }

    public List<ToolInfo> all() {
        return CATALOG.stream().map(t -> t.withReady(implementations.containsKey(t.id()))).toList();
    }

    public OsintTool get(String id) {
        OsintTool tool = implementations.get(id);
        if (tool == null) throw new IllegalArgumentException("Tool not available: " + id);
        return tool;
    }

    private static ToolInfo tool(String id, String name, String category, String description,
                                 boolean defaultOn, boolean slow, TargetType... accepts) {
        return new ToolInfo(id, name, category, description, Set.of(accepts), defaultOn, slow, false);
    }
}
