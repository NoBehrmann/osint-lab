package com.osintlab.tools.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.osintlab.support.HttpSupport;
import com.osintlab.support.Inputs;
import com.osintlab.support.Parallel;
import com.osintlab.support.UpstreamException;
import com.osintlab.tools.OsintTool;
import com.osintlab.tools.dns.DnsService;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Open ports, detected software (CPEs) and known vulnerabilities for an IP, from Shodan's free
 * InternetDB (keyless). The data comes from Shodan's own periodic internet-wide scans, so nothing is
 * sent to the target. CVE details (CVSS score, exploitation likelihood, "known exploited") come from
 * Shodan's free CVEDB.
 */
@Component
public class InternetDbTool implements OsintTool {

    private static final int MAX_IPS = 3;
    private static final int MAX_CVE_DETAILS = 25;

    private final HttpSupport http;
    private final DnsService dns;

    public InternetDbTool(HttpSupport http, DnsService dns) {
        this.http = http;
        this.dns = dns;
    }

    @Override
    public String id() {
        return "internetdb";
    }

    @Override
    public Duration cacheTtl() {
        return Duration.ofHours(24); // Shodan rescans roughly weekly
    }

    @Override
    public Object run(String target) throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> ips;
        if (Inputs.isIp(target)) {
            ips = List.of(target.trim());
        } else {
            String domain = Inputs.domain(target);
            ips = dns.query(domain, "A").stream().limit(MAX_IPS).toList();
            if (ips.isEmpty()) throw new UpstreamException(domain + " has no IPv4 address");
            out.put("resolvedFrom", domain);
        }

        List<Map<String, Object>> hosts = new ArrayList<>();
        Set<String> cves = new LinkedHashSet<>();
        for (String ip : ips) {
            Map<String, Object> host = new LinkedHashMap<>();
            host.put("ip", ip);
            if (!Inputs.isPublicIp(ip)) {
                host.put("note", "private or reserved address, not reachable from the internet, so Shodan has no data");
            } else {
                JsonNode db = http.getJsonOrNull("https://internetdb.shodan.io/" + ip, Duration.ofSeconds(15));
                if (db == null) {
                    host.put("note", "Shodan has no data for this IP: nothing exposed when it last scanned, or never scanned");
                } else {
                    host.put("ports", texts(db.path("ports")));
                    host.put("hostnames", texts(db.path("hostnames")));
                    host.put("software", texts(db.path("cpes")));
                    host.put("tags", texts(db.path("tags")));
                    host.put("vulnerabilities", db.path("vulns").size());
                    db.path("vulns").forEach(v -> cves.add(v.asText()));
                }
                host.put("shodan", "https://www.shodan.io/host/" + ip);
            }
            hosts.add(host);
        }
        out.put("hosts", hosts);

        if (!cves.isEmpty()) {
            out.put("vulnerabilities", cveDetails(cves));
            if (cves.size() > MAX_CVE_DETAILS) {
                out.put("vulnerabilitiesNote", "Details for " + MAX_CVE_DETAILS + " of " + cves.size() + " CVEs. Shodan "
                        + "lists them in no particular order, so the most severe may be among the rest (see the Shodan link)");
            }
        }
        out.put("note", "From Shodan's own scans, nothing is sent to the target. Data can be weeks old, and CVEs are "
                + "inferred from version banners, so they can be false positives (e.g. patched backports).");
        return out;
    }

    /** CVSS, EPSS and "known exploited" for each CVE, most severe first. */
    private List<Map<String, Object>> cveDetails(Set<String> cves) throws InterruptedException {
        Map<String, Callable<Object>> tasks = new LinkedHashMap<>();
        cves.stream().limit(MAX_CVE_DETAILS).forEach(cve ->
                tasks.put(cve, () -> http.getJsonOrNull("https://cvedb.shodan.io/cve/" + cve, Duration.ofSeconds(15))));

        List<Map<String, Object>> rows = new ArrayList<>();
        Parallel.all(tasks).forEach((cve, result) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("cve", cve);
            if (result instanceof JsonNode d) {
                row.put("cvss", d.path("cvss").isNumber() ? d.path("cvss").asDouble() : null);
                row.put("exploitLikelihood", d.path("epss").isNumber()
                        ? Math.round(d.path("epss").asDouble() * 1000) / 10.0 + "%" : null);
                row.put("knownExploited", d.path("kev").asBoolean());
                String summary = d.path("summary").asText("");
                row.put("summary", summary.length() > 220 ? summary.substring(0, 217) + "..." : summary);
            }
            row.put("details", "https://nvd.nist.gov/vuln/detail/" + cve);
            rows.add(row);
        });
        rows.sort(Comparator.comparing((Map<String, Object> r) ->
                r.get("cvss") instanceof Double d ? d : -1.0).reversed());
        return rows;
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(v -> out.add(v.asText()));
        return out;
    }
}
