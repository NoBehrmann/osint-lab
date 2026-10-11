package com.osintlab.tools.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.osintlab.support.HttpSupport;
import com.osintlab.support.Inputs;
import com.osintlab.support.UpstreamException;
import com.osintlab.tools.OsintTool;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registration data via RDAP (the JSON successor of WHOIS). rdap.org redirects to the
 * authoritative registry/RIR for the domain, IP or ASN.
 */
@Component
public class WhoisTool implements OsintTool {

    private final HttpSupport http;

    public WhoisTool(HttpSupport http) {
        this.http = http;
    }

    @Override
    public String id() {
        return "whois";
    }

    @Override
    public Duration cacheTtl() {
        return Duration.ofHours(24);
    }

    @Override
    public Object run(String target) throws Exception {
        String q = Inputs.require(target, "target");
        String path;
        String domain = null;
        if (Inputs.isIp(q)) path = "ip/" + q;
        else if (q.matches("(?i)^AS\\d{1,10}$")) path = "autnum/" + q.substring(2);
        else path = "domain/" + (domain = Inputs.domain(q));

        JsonNode n;
        try {
            n = http.getJson("https://rdap.org/" + path, Duration.ofSeconds(20), "application/rdap+json", 5_000_000);
        } catch (UpstreamException e) {
            // Several ccTLDs (.eu, .de, ...) have no RDAP service - fall back to classic WHOIS
            if (domain == null) throw e;
            return classicWhois(domain);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", q);
        out.put("source", "RDAP");
        putText(out, "name", n.has("ldhName") ? n.path("ldhName") : n.path("name"));
        putText(out, "handle", n.path("handle"));
        putText(out, "type", n.path("type"));
        putText(out, "country", n.path("country"));
        if (n.has("startAddress")) out.put("range", n.path("startAddress").asText() + " - " + n.path("endAddress").asText());
        if (n.has("startAutnum")) out.put("asRange", n.path("startAutnum").asText() + " - " + n.path("endAutnum").asText());
        if (n.has("status")) out.put("status", texts(n.path("status")));

        Map<String, String> events = new LinkedHashMap<>();
        for (JsonNode e : n.path("events")) events.put(e.path("eventAction").asText(), e.path("eventDate").asText());
        if (!events.isEmpty()) out.put("events", events);

        List<String> ns = new ArrayList<>();
        for (JsonNode s : n.path("nameservers")) ns.add(s.path("ldhName").asText().toLowerCase());
        if (!ns.isEmpty()) out.put("nameservers", ns);

        List<Map<String, Object>> contacts = new ArrayList<>();
        collectEntities(n.path("entities"), contacts, 0);
        if (!contacts.isEmpty()) out.put("contacts", contacts);

        if (n.path("secureDNS").has("delegationSigned")) {
            out.put("dnssec", n.path("secureDNS").path("delegationSigned").asBoolean());
        }
        out.put("raw", n);
        return out;
    }

    /** WHOIS over port 43: ask IANA for the TLD's WHOIS server, then query it. */
    private static Map<String, Object> classicWhois(String domain) throws Exception {
        String tld = domain.substring(domain.lastIndexOf('.') + 1);
        String server = whois43("whois.iana.org", tld).lines()
                .filter(l -> l.toLowerCase().startsWith("whois:"))
                .map(l -> l.substring(6).trim())
                .findFirst()
                .orElseThrow(() -> new UpstreamException("No RDAP or WHOIS service for ." + tld));
        String text = whois43(server, domain);

        // "Key: value" lines; indented lines under a "Section:" header get the section as prefix
        Map<String, String> fields = new LinkedHashMap<>();
        String section = "";
        for (String line : text.lines().toList()) {
            if (line.isBlank() || line.startsWith("%") || line.startsWith("#") || line.startsWith(">>>")) continue;
            boolean indented = Character.isWhitespace(line.charAt(0));
            String l = line.trim();
            int colon = l.indexOf(':');
            if (!indented) section = "";
            if (colon > 0 && colon < 40) {
                String key = l.substring(0, colon).trim();
                String value = l.substring(colon + 1).trim();
                if (value.isEmpty() && !indented) {
                    section = key;
                    continue;
                }
                String fullKey = section.isEmpty() || !indented ? key : section + " " + key;
                fields.merge(fullKey, value, (a, b) -> a.contains(b) ? a : a + ", " + b);
            } else if (indented && !section.isEmpty()) {
                fields.merge(section, l, (a, b) -> a + ", " + b);
            }
            if (fields.size() >= 60) break;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", domain);
        out.put("source", "WHOIS (" + server + ")");
        out.put("fields", fields);
        out.put("raw", text);
        return out;
    }

    private static String whois43(String server, String query) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(server, 43), 8000);
            socket.setSoTimeout(10000);
            socket.getOutputStream().write((query + "\r\n").getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            try (InputStream in = socket.getInputStream()) {
                return new String(in.readNBytes(200_000), StandardCharsets.UTF_8);
            }
        }
    }

    /** Flattens nested RDAP entities (registrar -> abuse contact etc.) into rows. */
    private static void collectEntities(JsonNode entities, List<Map<String, Object>> out, int depth) {
        if (depth > 3) return;
        for (JsonNode e : entities) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("roles", String.join(", ", texts(e.path("roles"))));
            row.put("handle", e.path("handle").asText(null));
            for (JsonNode prop : e.path("vcardArray").path(1)) {
                String key = prop.path(0).asText();
                JsonNode value = prop.path(3);
                switch (key) {
                    case "fn" -> row.put("name", value.asText());
                    case "org" -> row.put("org", value.isArray() ? value.path(0).asText() : value.asText());
                    case "email" -> row.put("email", value.asText());
                    case "tel" -> row.put("phone", value.asText());
                    case "adr" -> {
                        String label = prop.path(1).path("label").asText("");
                        if (!label.isBlank()) row.put("address", label.replace("\n", ", "));
                        else if (value.isArray()) row.put("address", joinNonBlank(value));
                    }
                    default -> {
                    }
                }
            }
            out.add(row);
            collectEntities(e.path("entities"), out, depth + 1);
        }
    }

    private static String joinNonBlank(JsonNode arr) {
        List<String> parts = new ArrayList<>();
        for (JsonNode p : arr) {
            String s = p.isArray() ? joinNonBlank(p) : p.asText("");
            if (!s.isBlank()) parts.add(s);
        }
        return String.join(", ", parts);
    }

    private static List<String> texts(JsonNode arr) {
        List<String> out = new ArrayList<>();
        for (JsonNode s : arr) out.add(s.asText());
        return out;
    }

    private static void putText(Map<String, Object> out, String key, JsonNode node) {
        if (!node.isMissingNode() && !node.isNull() && !node.asText().isBlank()) out.put(key, node.asText());
    }
}
