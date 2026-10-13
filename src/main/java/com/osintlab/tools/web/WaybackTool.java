package com.osintlab.tools.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.osintlab.support.HttpSupport;
import com.osintlab.support.Inputs;
import com.osintlab.support.UpstreamException;
import com.osintlab.tools.OsintTool;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Wayback Machine (archive.org CDX API): a monthly snapshot timeline for the target, plus the
 * list of distinct archived URLs when the target is a whole domain.
 */
@Component
public class WaybackTool implements OsintTool {

    private static final String CDX = "https://web.archive.org/cdx/search/cdx";

    private final HttpSupport http;

    public WaybackTool(HttpSupport http) {
        this.http = http;
    }

    @Override
    public String id() {
        return "wayback";
    }

    @Override
    public int maxConcurrent() {
        return 2;
    }

    @Override
    public Duration cacheTtl() {
        return Duration.ofHours(24);
    }

    @Override
    public Object run(String target) throws Exception {
        URI uri = Inputs.url(target);
        boolean wholeDomain = uri.getPath() == null || uri.getPath().isEmpty() || uri.getPath().equals("/");
        String host = uri.getHost().toLowerCase();
        String key = wholeDomain ? host : host + uri.getRawPath();

        // both CDX queries run in parallel, archive.org is slow
        CompletableFuture<JsonNode> urlsQuery = wholeDomain
                ? CompletableFuture.supplyAsync(() -> cdx("?url=" + enc(host) + "&matchType=domain&output=json"
                + "&fl=original&collapse=urlkey&filter=statuscode:200&limit=500"), r -> Thread.ofVirtual().start(r))
                : null;
        // one capture per month
        JsonNode timeline = cdx("?url=" + enc(key) + "&output=json&fl=timestamp,original,statuscode"
                + "&collapse=timestamp:6&limit=1000");

        List<Map<String, Object>> snapshots = new ArrayList<>();
        for (int i = 1; i < timeline.size(); i++) { // row 0 is the header
            JsonNode row = timeline.get(i);
            String ts = row.path(0).asText();
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("date", ts.substring(0, 4) + "-" + ts.substring(4, 6) + "-" + ts.substring(6, 8));
            s.put("status", row.path(2).asText());
            s.put("snapshot", "https://web.archive.org/web/" + ts + "/" + row.path(1).asText());
            snapshots.add(s);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("target", key);
        out.put("months", snapshots.size());
        if (!snapshots.isEmpty()) {
            out.put("firstSeen", snapshots.getFirst().get("date"));
            out.put("lastSeen", snapshots.getLast().get("date"));
        }
        out.put("calendar", "https://web.archive.org/web/*/" + key + "*");

        if (urlsQuery != null) {
            JsonNode urls;
            try {
                urls = urlsQuery.join();
            } catch (CompletionException e) {
                throw e.getCause() instanceof Exception cause ? cause : e;
            }
            Set<String> list = new LinkedHashSet<>();
            for (int i = 1; i < urls.size(); i++) list.add(urls.get(i).path(0).asText());
            out.put("archivedUrls", list.size() >= 500 ? list.size() + "+ (first 500 shown)" : list.size());
            out.put("urls", list);
        }
        out.put("timeline", snapshots.reversed());
        return out;
    }

    private JsonNode cdx(String query) {
        try {
            return http.getJson(CDX + query, Duration.ofSeconds(60));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new UpstreamException("archive.org request failed: " + e.getMessage());
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
