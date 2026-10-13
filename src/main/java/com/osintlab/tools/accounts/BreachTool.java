package com.osintlab.tools.accounts;

import com.fasterxml.jackson.databind.JsonNode;
import com.osintlab.support.HttpSupport;
import com.osintlab.support.Inputs;
import com.osintlab.support.Parallel;
import com.osintlab.tools.OsintTool;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Which known data breaches an email address (or username) appears in, from two free keyless
 * services: XposedOrNot (email: breach names, dates, exposed data types, risk score) and the
 * LeakCheck public API (email or username: source names and dates). Only breach NAMES and data
 * types are shown, never the leaked data itself.
 */
@Component
public class BreachTool implements OsintTool {

    private final HttpSupport http;

    public BreachTool(HttpSupport http) {
        this.http = http;
    }

    @Override
    public String id() {
        return "breaches";
    }

    @Override
    public int maxConcurrent() {
        return 2; // free community services, be gentle
    }

    @Override
    public Duration cacheTtl() {
        return Duration.ofHours(24);
    }

    @Override
    public Object run(String target) throws Exception {
        String t = Inputs.require(target, "target");
        boolean email = t.contains("@");
        String value = email ? Inputs.email(t) : Inputs.username(t);
        String q = URLEncoder.encode(value, StandardCharsets.UTF_8);

        Map<String, Callable<Object>> tasks = new LinkedHashMap<>();
        if (email) tasks.put("XposedOrNot", () -> xposedOrNot(q));
        tasks.put("LeakCheck", () -> leakCheck(q));
        Map<String, Object> sources = Parallel.all(tasks);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put(email ? "email" : "username", value);
        out.put("summary", summary(sources));
        sources.forEach(out::put);
        if (!email) out.put("XposedOrNot", "email addresses only");
        out.put("note", "Only breach names and the TYPES of data exposed are shown, no passwords or leaked records. "
                + "A hit means the value appears in that breach's data, not that an account is still compromised."
                + (email ? "" : " A username match can be a different person who uses the same name."));
        return out;
    }

    private Object xposedOrNot(String q) throws Exception {
        JsonNode d = http.getJsonOrNull("https://api.xposedornot.com/v1/breach-analytics?email=" + q,
                Duration.ofSeconds(20));
        JsonNode details = d == null ? null : d.path("ExposedBreaches").path("breaches_details");
        if (details == null || !details.isArray() || details.isEmpty()) return Map.of("breaches", 0);

        List<Map<String, Object>> breaches = new ArrayList<>();
        for (JsonNode b : details) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("breach", b.path("breach").asText());
            row.put("year", b.path("xposed_date").asText());
            row.put("domain", b.path("domain").asText(""));
            row.put("industry", b.path("industry").asText(""));
            row.put("exposedData", String.join(", ", Arrays.stream(b.path("xposed_data").asText("").split(";"))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList()));
            row.put("records", b.path("xposed_records").asLong());
            row.put("passwordStorage", b.path("password_risk").asText(""));
            row.put("verified", "Yes".equalsIgnoreCase(b.path("verified").asText()));
            breaches.add(row);
        }
        breaches.sort(Comparator.comparing((Map<String, Object> r) -> String.valueOf(r.get("year"))).reversed());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("breaches", breaches.size());
        JsonNode risk = d.path("BreachMetrics").path("risk").path(0);
        if (!risk.isMissingNode()) {
            out.put("risk", risk.path("risk_label").asText() + " (" + risk.path("risk_score").asInt() + "/100)");
        }
        out.put("list", breaches);
        return out;
    }

    private Object leakCheck(String q) throws Exception {
        JsonNode d = http.getJsonOrNull("https://leakcheck.io/api/public?check=" + q, Duration.ofSeconds(20));
        if (d == null || !d.path("success").asBoolean()) {
            String err = d == null ? "" : d.path("error").asText("");
            if (err.isEmpty() || err.equalsIgnoreCase("Not found")) return Map.of("sources", 0);
            return Map.of("error", err);
        }
        List<Map<String, Object>> sources = new ArrayList<>();
        for (JsonNode s : d.path("sources")) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("source", s.path("name").asText());
            row.put("date", s.path("date").asText(""));
            sources.add(row);
        }
        sources.sort(Comparator.comparing((Map<String, Object> r) -> String.valueOf(r.get("date"))).reversed());
        List<String> fields = new ArrayList<>();
        d.path("fields").forEach(f -> fields.add(f.asText()));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sources", sources.size());
        out.put("exposedDataTypes", String.join(", ", fields));
        out.put("list", sources);
        return out;
    }

    private static String summary(Map<String, Object> sources) {
        List<String> parts = new ArrayList<>();
        sources.forEach((name, r) -> {
            if (Parallel.failed(r)) parts.add(name + ": unavailable");
            else if (r instanceof Map<?, ?> m) {
                Object n = m.containsKey("breaches") ? m.get("breaches") : m.get("sources");
                parts.add(name + ": " + n + " breach" + ("1".equals(String.valueOf(n)) ? "" : "es"));
            }
        });
        return String.join(" | ", parts);
    }
}
