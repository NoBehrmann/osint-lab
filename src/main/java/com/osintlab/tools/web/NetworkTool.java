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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Who runs the network an IP sits in, and whether it's on spam / abuse blacklists:
 * <ul>
 *   <li>RIPEstat (keyless, all regions): ASN and its holder, announced prefix, responsible registry,
 *       abuse contact, RPKI route validity.</li>
 *   <li>DNS blacklists (DNSBL): plain DNS lookups of the reversed IP - nothing is sent to the target.
 *       For a domain, Spamhaus's domain blocklist (DBL) is checked too.</li>
 * </ul>
 * Spamhaus refuses queries relayed through big public resolvers (8.8.8.8, 1.1.1.1, 9.9.9.9); this
 * shows as "query refused" rather than a false "not listed".
 */
@Component
public class NetworkTool implements OsintTool {

    private static final String RIPE = "https://stat.ripe.net/data/";
    private static final String APP = "&sourceapp=osint-lab";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    /** name -> zone */
    private static final Map<String, String> IP_LISTS = Map.of(
            "Spamhaus ZEN", "zen.spamhaus.org",
            "Barracuda", "b.barracudacentral.org",
            "SpamCop", "bl.spamcop.net",
            "PSBL", "psbl.surriel.com",
            "UCEPROTECT L1", "dnsbl-1.uceprotect.net");

    private final HttpSupport http;
    private final DnsService dns;

    public NetworkTool(HttpSupport http, DnsService dns) {
        this.http = http;
        this.dns = dns;
    }

    @Override
    public String id() {
        return "network";
    }

    @Override
    public Duration cacheTtl() {
        return Duration.ofHours(6); // blacklist listings change quickly
    }

    @Override
    public Object run(String target) throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        String ip;
        String domain = null;
        if (Inputs.isIp(target)) {
            ip = target.trim();
        } else {
            domain = Inputs.domain(target);
            List<String> a = dns.query(domain, "A");
            if (a.isEmpty()) a = dns.query(domain, "AAAA");
            ip = a.isEmpty() ? null : a.get(0); // no address: the domain blocklist can still be checked
            out.put("resolvedFrom", domain);
        }
        if (ip != null && !Inputs.isPublicIp(ip)) {
            throw new IllegalArgumentException(ip + " is a private or reserved address - there is no public network data for it");
        }
        out.put("ip", ip == null ? "none - the domain has no address, so only the domain blocklist was checked" : ip);

        String d = domain;
        Map<String, Callable<Object>> tasks = new LinkedHashMap<>();
        if (ip != null) {
            tasks.put("network", () -> network(ip));
            tasks.put("blacklists", () -> ipBlacklists(ip));
        }
        if (d != null) tasks.put("domainBlacklist", () -> domainBlacklist(d));
        Map<String, Object> r = Parallel.all(tasks);
        out.putAll(r);

        out.put("note", "Blacklist checks are DNS lookups - nothing is sent to the target. Policy listings "
                + "(e.g. Spamhaus PBL for home internet ranges) are normal and not a sign of abuse.");
        return out;
    }

    private Object network(String ip) throws Exception {
        JsonNode info = ripe("network-info", "resource=" + ip);
        String asn = info.path("asns").path(0).asText("");
        String prefix = info.path("prefix").asText("");

        Map<String, Callable<Object>> tasks = new LinkedHashMap<>();
        tasks.put("abuse", () -> ripe("abuse-contact-finder", "resource=" + ip));
        if (!asn.isEmpty()) tasks.put("as", () -> ripe("as-overview", "resource=AS" + asn));
        if (!asn.isEmpty() && !prefix.isEmpty()) {
            tasks.put("rpki", () -> ripe("rpki-validation", "resource=AS" + asn + "&prefix=" + prefix));
        }
        Map<String, Object> r = Parallel.all(tasks);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("asn", asn.isEmpty() ? "not announced" : "AS" + asn);
        if (r.get("as") instanceof JsonNode as) out.put("holder", as.path("holder").asText());
        else if (r.containsKey("as")) out.put("holder", unavailable(r.get("as")));
        out.put("prefix", prefix.isEmpty() ? null : prefix);
        if (r.get("abuse") instanceof JsonNode abuse) {
            List<String> contacts = new ArrayList<>();
            abuse.path("abuse_contacts").forEach(c -> contacts.add(c.asText()));
            out.put("abuseContact", contacts.isEmpty() ? null : String.join(", ", contacts));
            out.put("registry", abuse.path("authoritative_rir").asText("").toUpperCase());
        } else {
            out.put("abuseContact", unavailable(r.get("abuse")));
        }
        if (r.get("rpki") instanceof JsonNode rpki) {
            // "invalid" means the route announcement isn't authorised - a possible hijack or misconfiguration
            out.put("routeValidity (RPKI)", rpki.path("status").asText());
        } else if (r.containsKey("rpki")) {
            out.put("routeValidity (RPKI)", unavailable(r.get("rpki")));
        }
        if (!asn.isEmpty()) out.put("asDetails", "https://bgp.he.net/AS" + asn);
        return out;
    }

    /** A failed sub-lookup is shown as such instead of silently leaving the field out. */
    private static String unavailable(Object failed) {
        return "unavailable (" + (failed instanceof Map<?, ?> m ? m.get("error") : "no answer") + ")";
    }

    private JsonNode ripe(String call, String params) throws Exception {
        JsonNode d = http.getJsonOrNull(RIPE + call + "/data.json?" + params + APP, TIMEOUT);
        if (d == null || !"ok".equals(d.path("status").asText())) {
            throw new UpstreamException("RIPEstat " + call + " failed");
        }
        return d.path("data");
    }

    private Object ipBlacklists(String ip) throws Exception {
        if (ip.contains(":")) return "IPv6 - most DNS blacklists only cover IPv4";
        String[] o = ip.split("\\.");
        String reversed = o[3] + "." + o[2] + "." + o[1] + "." + o[0];

        Map<String, Callable<Object>> tasks = new LinkedHashMap<>();
        IP_LISTS.forEach((name, zone) -> tasks.put(name, () -> dns.queryStrict(reversed + "." + zone, "A")));
        Map<String, Object> answers = Parallel.all(tasks);

        List<Map<String, Object>> rows = new ArrayList<>();
        int listed = 0;
        for (String name : IP_LISTS.keySet().stream().sorted().toList()) {
            Object a = answers.get(name);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("list", name);
            if (Parallel.failed(a)) {
                row.put("result", "no answer (DNS timeout)");
            } else {
                @SuppressWarnings("unchecked") List<String> codes = (List<String>) a;
                String verdict = interpret(name, codes);
                row.put("result", verdict);
                if (verdict.startsWith("LISTED")) listed++;
            }
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", listed == 0 ? "not listed on any of " + rows.size() : "listed on " + listed + " of " + rows.size());
        out.put("lists", rows);
        return out;
    }

    private static String interpret(String list, List<String> codes) {
        if (codes.isEmpty()) return "not listed";
        if (codes.stream().anyMatch(c -> c.startsWith("127.255.255."))) {
            return "query refused - your DNS goes through a public resolver this list blocks";
        }
        if (!list.startsWith("Spamhaus")) return "LISTED (" + String.join(", ", codes) + ")";
        List<String> parts = new ArrayList<>();
        for (String c : codes) {
            switch (c) {
                case "127.0.0.2" -> parts.add("SBL: known spam source");
                case "127.0.0.3" -> parts.add("CSS: recent spam emitter");
                case "127.0.0.4", "127.0.0.5", "127.0.0.6", "127.0.0.7" -> parts.add("XBL: infected / exploited host");
                case "127.0.0.9" -> parts.add("DROP: hijacked or criminal network");
                case "127.0.0.10", "127.0.0.11" -> parts.add("PBL: end-user IP range (policy, not abuse)");
                default -> parts.add(c);
            }
        }
        boolean policyOnly = codes.stream().allMatch(c -> c.equals("127.0.0.10") || c.equals("127.0.0.11"));
        return (policyOnly ? "policy listing - " : "LISTED - ") + String.join("; ", parts);
    }

    private Object domainBlacklist(String domain) throws Exception {
        List<String> codes = dns.queryStrict(domain + ".dbl.spamhaus.org", "A");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("list", "Spamhaus DBL");
        if (codes.isEmpty()) {
            out.put("result", "not listed");
        } else if (codes.stream().anyMatch(c -> c.startsWith("127.255.255."))) {
            out.put("result", "query refused - your DNS goes through a public resolver Spamhaus blocks");
        } else {
            List<String> parts = new ArrayList<>();
            for (String c : codes) {
                switch (c) {
                    case "127.0.1.2" -> parts.add("spam domain");
                    case "127.0.1.4" -> parts.add("phishing domain");
                    case "127.0.1.5" -> parts.add("malware domain");
                    case "127.0.1.6" -> parts.add("botnet C&C domain");
                    case "127.0.1.102", "127.0.1.103", "127.0.1.104", "127.0.1.105", "127.0.1.106" ->
                            parts.add("abused legitimate domain");
                    default -> parts.add(c);
                }
            }
            out.put("result", "LISTED - " + String.join("; ", parts));
        }
        return out;
    }
}
