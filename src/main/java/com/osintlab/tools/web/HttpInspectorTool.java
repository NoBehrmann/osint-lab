package com.osintlab.tools.web;

import com.osintlab.support.HttpSupport;
import com.osintlab.support.Inputs;
import com.osintlab.tools.OsintTool;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpHeaders;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches a page and reports redirects, headers, security headers, technology fingerprints,
 * page metadata, emails/social links found in the HTML, robots.txt and security.txt.
 */
@Component
public class HttpInspectorTool implements OsintTool {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final int MAX_REDIRECTS = 10;
    private static final List<String> SECURITY_HEADERS = List.of(
            "strict-transport-security", "content-security-policy", "x-frame-options",
            "x-content-type-options", "referrer-policy", "permissions-policy");

    private static final Pattern TITLE = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
    private static final Pattern META = Pattern.compile(
            "(?is)<meta\\s+[^>]*(?:name|property)=[\"']([^\"']+)[\"'][^>]*content=[\"']([^\"']*)[\"']");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern SOCIAL = Pattern.compile(
            "https?://(?:www\\.)?(?:facebook\\.com|instagram\\.com|x\\.com|twitter\\.com|linkedin\\.com|"
                    + "youtube\\.com|tiktok\\.com|github\\.com|t\\.me|xing\\.com|pinterest\\.com)/[^\"'\\s<>)]+");

    /** body/header/cookie substring -> technology */
    private static final Map<String, String> BODY_SIGNATURES = Map.ofEntries(
            Map.entry("wp-content/", "WordPress"), Map.entry("/_next/", "Next.js"), Map.entry("__NUXT__", "Nuxt"),
            Map.entry("ng-version=", "Angular"), Map.entry("data-reactroot", "React"),
            Map.entry("cdn.shopify.com", "Shopify"), Map.entry("static.wixstatic.com", "Wix"),
            Map.entry("squarespace.com", "Squarespace"), Map.entry("/sites/default/files", "Drupal"),
            Map.entry("/media/jui/", "Joomla"), Map.entry("jquery", "jQuery"), Map.entry("bootstrap", "Bootstrap"),
            Map.entry("googletagmanager.com", "Google Tag Manager"), Map.entry("google-analytics.com", "Google Analytics"),
            Map.entry("static.hotjar.com", "Hotjar"), Map.entry("js.hs-scripts.com", "HubSpot"),
            Map.entry("cdn.cookielaw.org", "OneTrust"), Map.entry("consent.cookiebot.com", "Cookiebot"),
            Map.entry("typo3", "TYPO3"), Map.entry("webflow", "Webflow"), Map.entry("gatsby", "Gatsby"));
    private static final Map<String, String> COOKIE_SIGNATURES = Map.of(
            "phpsessid", "PHP", "jsessionid", "Java", "asp.net_sessionid", "ASP.NET",
            "laravel_session", "Laravel", "csrftoken", "Django (likely)", "_shopify", "Shopify",
            "__cf_bm", "Cloudflare Bot Management");

    private final HttpSupport http;

    public HttpInspectorTool(HttpSupport http) {
        this.http = http;
    }

    @Override
    public String id() {
        return "http";
    }

    @Override
    public Object run(String target) throws Exception {
        URI uri = Inputs.url(target);

        List<Map<String, Object>> redirects = new ArrayList<>();
        HttpSupport.Fetched page = null;
        URI current = uri;
        for (int i = 0; i <= MAX_REDIRECTS; i++) {
            page = http.get(current, TIMEOUT, "text/html,*/*", 2_000_000, false);
            Map<String, Object> hop = new LinkedHashMap<>();
            hop.put("url", current.toString());
            hop.put("status", page.status());
            redirects.add(hop);
            var location = page.headers().firstValue("location");
            if (page.status() >= 300 && page.status() < 400 && location.isPresent()) {
                current = current.resolve(location.get().trim().replace(" ", "%20"));
            } else {
                break;
            }
        }

        HttpHeaders h = page.headers();
        String body = page.body();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("finalUrl", current.toString());
        out.put("status", page.status());
        if (redirects.size() > 1) out.put("redirects", redirects);

        Map<String, String> meta = new LinkedHashMap<>();
        Matcher t = TITLE.matcher(body);
        if (t.find()) meta.put("title", clean(t.group(1)));
        Matcher m = META.matcher(body);
        while (m.find() && meta.size() < 15) {
            String name = m.group(1).toLowerCase(Locale.ROOT);
            if (name.matches("description|generator|author|keywords|og:title|og:site_name|twitter:site|application-name")) {
                meta.putIfAbsent(name, clean(m.group(2)));
            }
        }
        out.put("page", meta);

        out.put("technologies", technologies(h, body));

        Map<String, String> security = new LinkedHashMap<>();
        for (String name : SECURITY_HEADERS) security.put(name, h.firstValue(name).orElse("MISSING"));
        out.put("securityHeaders", security);

        Set<String> emails = new LinkedHashSet<>();
        Matcher e = EMAIL.matcher(body);
        while (e.find() && emails.size() < 30) {
            String found = e.group().toLowerCase(Locale.ROOT);
            if (!found.matches(".*\\.(png|jpg|jpeg|gif|svg|webp|css|js)$")) emails.add(found);
        }
        out.put("emailsOnPage", emails);

        Set<String> social = new LinkedHashSet<>();
        Matcher s = SOCIAL.matcher(body);
        while (s.find() && social.size() < 30) social.add(s.group());
        out.put("socialLinks", social);

        Map<String, String> headers = new TreeMap<>();
        h.map().forEach((k, v) -> {
            if (!k.equals(":status")) headers.put(k, String.join("; ", v));
        });
        out.put("headers", headers);

        URI base = current.resolve("/");
        out.put("robotsTxt", textFile(base.resolve("/robots.txt")));
        out.put("securityTxt", textFile(base.resolve("/.well-known/security.txt")));
        return out;
    }

    private static List<String> technologies(HttpHeaders h, String body) {
        Set<String> tech = new LinkedHashSet<>();
        h.firstValue("server").ifPresent(v -> tech.add("Server: " + v));
        h.firstValue("x-powered-by").ifPresent(v -> tech.add("Powered by: " + v));
        h.firstValue("x-generator").ifPresent(v -> tech.add("Generator: " + v));
        if (h.firstValue("cf-ray").isPresent()) tech.add("Cloudflare");
        if (h.firstValue("x-amz-cf-id").isPresent()) tech.add("Amazon CloudFront");
        if (h.firstValue("x-vercel-id").isPresent()) tech.add("Vercel");
        if (h.firstValue("x-nf-request-id").isPresent()) tech.add("Netlify");
        if (h.firstValue("x-github-request-id").isPresent()) tech.add("GitHub Pages");
        if (h.firstValue("x-azure-ref").isPresent()) tech.add("Azure Front Door");
        if (h.allValues("via").stream().anyMatch(v -> v.toLowerCase().contains("varnish"))) tech.add("Varnish");

        String cookies = String.join(";", h.allValues("set-cookie")).toLowerCase(Locale.ROOT);
        COOKIE_SIGNATURES.forEach((sig, name) -> {
            if (cookies.contains(sig)) tech.add(name);
        });
        String lower = body.toLowerCase(Locale.ROOT);
        BODY_SIGNATURES.forEach((sig, name) -> {
            if (lower.contains(sig.toLowerCase(Locale.ROOT))) tech.add(name);
        });
        return new ArrayList<>(tech);
    }

    private Object textFile(URI uri) {
        try {
            HttpSupport.Fetched f = http.get(uri, Duration.ofSeconds(8), "text/plain", 4000, true);
            if (f.status() != 200 || f.body().trim().startsWith("<")) return "not found (HTTP " + f.status() + ")";
            Map<String, String> m = new LinkedHashMap<>();
            m.put("url", uri.toString());
            m.put("content", f.body().length() >= 4000 ? f.body() + "\n... (truncated)" : f.body());
            return m;
        } catch (Exception e) {
            return "could not fetch: " + e.getMessage();
        }
    }

    private static String clean(String s) {
        return s.replaceAll("\\s+", " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").trim();
    }
}
