package com.osintlab.tools.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.osintlab.support.HttpSupport;
import com.osintlab.support.Inputs;
import com.osintlab.support.UpstreamException;
import com.osintlab.tools.OsintTool;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Subdomains from Certificate Transparency logs (crt.sh), resolved to IPs to show which are live.
 */
@Component
public class SubdomainTool implements OsintTool {

    private static final int MAX_RESOLVE = 300;
    private static final Duration RESOLVE_BUDGET = Duration.ofSeconds(20);

    private final HttpSupport http;

    public SubdomainTool(HttpSupport http) {
        this.http = http;
    }

    @Override
    public String id() {
        return "subdomains";
    }

    @Override
    public int maxConcurrent() {
        return 2; // crt.sh is slow and easily overloaded
    }

    @Override
    public Duration cacheTtl() {
        return Duration.ofHours(24);
    }

    @Override
    public Object run(String target) throws Exception {
        String domain = Inputs.domain(target);
        String source = "crt.sh";
        List<Cert> certs;
        try {
            certs = fromCrtSh(domain);
        } catch (UpstreamException e) {
            // crt.sh is frequently overloaded (502/503) - CertSpotter has the same CT data
            source = "CertSpotter (crt.sh unavailable: " + e.getMessage() + ")";
            certs = fromCertSpotter(domain);
        }

        Map<String, Map<String, Object>> names = new TreeMap<>();
        for (Cert cert : certs) {
            String notBefore = cert.notBefore();
            for (String raw : cert.names()) {
                String name = raw.trim().toLowerCase();
                boolean wildcard = name.startsWith("*.");
                if (wildcard) name = name.substring(2);
                if (!name.equals(domain) && !name.endsWith("." + domain)) continue;

                Map<String, Object> row = names.computeIfAbsent(name, n -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("name", n);
                    r.put("ips", null);
                    r.put("wildcard", false);
                    r.put("certificates", 0);
                    r.put("firstSeen", notBefore);
                    r.put("lastSeen", notBefore);
                    return r;
                });
                if (wildcard) row.put("wildcard", true);
                row.merge("certificates", 1, (a, b) -> (Integer) a + (Integer) b);
                if (notBefore.compareTo((String) row.get("firstSeen")) < 0) row.put("firstSeen", notBefore);
                if (notBefore.compareTo((String) row.get("lastSeen")) > 0) row.put("lastSeen", notBefore);
            }
        }
        for (Map<String, Object> row : names.values()) {
            row.put("firstSeen", ((String) row.get("firstSeen")).replace('T', ' '));
            row.put("lastSeen", ((String) row.get("lastSeen")).replace('T', ' '));
        }

        int live = resolve(names);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("domain", domain);
        out.put("source", source);
        out.put("certificatesScanned", certs.size());
        out.put("uniqueNames", names.size());
        out.put("resolving", live);
        if (names.size() > MAX_RESOLVE) out.put("note", "Only the first " + MAX_RESOLVE + " names were resolved");
        out.put("subdomains", new ArrayList<>(names.values()));
        return out;
    }

    private record Cert(List<String> names, String notBefore) {
    }

    /** crt.sh, with one retry - it often answers 502 under load. */
    private List<Cert> fromCrtSh(String domain) throws Exception {
        String url = "https://crt.sh/?q=" + URLEncoder.encode("%." + domain, StandardCharsets.UTF_8) + "&output=json";
        JsonNode json;
        try {
            json = http.getJson(url, Duration.ofSeconds(90));
        } catch (UpstreamException first) {
            Thread.sleep(2000);
            json = http.getJson(url, Duration.ofSeconds(90));
        }
        List<Cert> certs = new ArrayList<>();
        for (JsonNode c : json) {
            certs.add(new Cert(List.of(c.path("name_value").asText("").split("\\n")), c.path("not_before").asText("")));
        }
        return certs;
    }

    /** CertSpotter free API (no key, ~100 requests/hour); up to 10 pages of 100 certificates. */
    private List<Cert> fromCertSpotter(String domain) throws Exception {
        List<Cert> certs = new ArrayList<>();
        String after = null;
        for (int page = 0; page < 10; page++) {
            String url = "https://api.certspotter.com/v1/issuances?domain=" + domain
                    + "&include_subdomains=true&expand=dns_names" + (after == null ? "" : "&after=" + after);
            JsonNode json = http.getJson(url, Duration.ofSeconds(30));
            if (json.isEmpty()) break;
            for (JsonNode c : json) {
                List<String> names = new ArrayList<>();
                c.path("dns_names").forEach(n -> names.add(n.asText()));
                certs.add(new Cert(names, c.path("not_before").asText("").replace("Z", "")));
                after = c.path("id").asText();
            }
            if (json.size() < 100) break;
        }
        return certs;
    }

    /** Resolves names in parallel within a fixed time budget; returns how many resolved. */
    private static int resolve(Map<String, Map<String, Object>> names) {
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Map<Map<String, Object>, Future<List<String>>> futures = new LinkedHashMap<>();
            names.values().stream().limit(MAX_RESOLVE).forEach(row -> futures.put(row, pool.submit(() ->
                    Arrays.stream(InetAddress.getAllByName((String) row.get("name")))
                            .map(InetAddress::getHostAddress).distinct().toList())));

            long deadline = System.nanoTime() + RESOLVE_BUDGET.toNanos();
            int live = 0;
            for (var entry : futures.entrySet()) {
                try {
                    List<String> ips = entry.getValue().get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                    entry.getKey().put("ips", String.join(", ", ips));
                    live++;
                } catch (Exception e) {
                    entry.getKey().put("ips", null); // NXDOMAIN or out of time
                }
            }
            return live;
        } finally {
            pool.shutdownNow(); // don't wait for hung lookups
        }
    }
}
