package com.osintlab.tools.accounts;

import com.osintlab.support.Csv;
import com.osintlab.support.HttpSupport;
import com.osintlab.support.Inputs;
import com.osintlab.support.ProcessRunner;
import com.osintlab.support.PythonTools;
import com.osintlab.tools.OsintTool;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Employees of a company via search engines indexing LinkedIn profiles - no LinkedIn login needed.
 * Runs CrossLinked (https://github.com/m8sec/CrossLinked, Google + Bing) and adds results from
 * DuckDuckGo, since Google/Bing often block or change their result pages.
 */
@Component
public class CrossLinkedTool implements OsintTool {

    /** Query variants - DuckDuckGo's HTML endpoint returns ~10 results per query without pagination. */
    private static final List<String> VARIANTS = List.of("", "engineer", "manager", "sales", "marketing",
            "developer", "consultant", "CEO OR founder OR director", "HR OR recruiter", "student OR intern");
    private static final Pattern RESULT = Pattern.compile(
            "class=\"result__a\" href=\"(https://[a-z]{2,3}\\.linkedin\\.com/in/[^\"?]+)[^\"]*\">([^<]+)<");

    private final ProcessRunner runner;
    private final PythonTools python;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    public CrossLinkedTool(ProcessRunner runner, PythonTools python) {
        this.runner = runner;
        this.python = python;
    }

    @Override
    public String id() {
        return "crosslinked";
    }

    @Override
    public int maxConcurrent() {
        return 1;
    }

    @Override
    public Duration cacheTtl() {
        return Duration.ofHours(24);
    }

    @Override
    public Object run(String target) throws Exception {
        String company = Inputs.company(target);
        // CrossLinked treats a name ending in ".csv" as an input file
        if (company.toLowerCase().endsWith(".csv")) throw new IllegalArgumentException("Not a valid company name");

        Map<String, Map<String, Object>> people = new LinkedHashMap<>(); // keyed by profile URL
        List<String> command = List.of(python.executable("crosslinked"),
                "-f", "{first} {last}", "-t", "15", "-j", "1", "--", company);
        List<String> blocked = new ArrayList<>();
        int crossLinked = runner.run(command, Duration.ofMinutes(4), r -> {
            if ((r.stdout() + r.stderr()).contains("(429)")) blocked.add("Google (rate limit)");
            return readCrossLinked(r.workDir(), people);
        });
        int duckDuckGo = searchDuckDuckGo(company, people, blocked);

        String needle = company.toLowerCase(Locale.ROOT).replaceAll("\\b(gmbh|ag|inc|ltd|llc|corp|co|kg|se)\\b\\.?", "").trim();
        List<Map<String, Object>> list = new ArrayList<>(people.values());
        list.forEach(p -> p.put("mentionsCompany",
                String.valueOf(p.get("title")).toLowerCase(Locale.ROOT).contains(needle)));
        list.sort((a, b) -> Boolean.compare((Boolean) b.get("mentionsCompany"), (Boolean) a.get("mentionsCompany")));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("company", company);
        out.put("found", list.size());
        out.put("sources", Map.of("CrossLinked (Google/Bing)", crossLinked, "DuckDuckGo", duckDuckGo));
        if (!blocked.isEmpty()) out.put("blockedBy", blocked);
        out.put("people", list);
        out.put("note", list.isEmpty()
                ? "No results. " + (blocked.isEmpty() ? "" : "Search engines are blocking automated queries from "
                + "this IP right now (see blockedBy) - try again in an hour or two. ")
                + "Search-engine based, so small companies often have few indexed profiles."
                : "From search-engine snippets of LinkedIn profiles: may include former employees or similarly "
                + "named companies. 'mentionsCompany' = the snippet names the company.");
        return out;
    }

    private static int readCrossLinked(Path workDir, Map<String, Map<String, Object>> people) throws Exception {
        Path csv = workDir.resolve("names.csv");
        if (!Files.exists(csv)) return 0;
        int added = 0;
        for (String line : Files.readAllLines(csv)) {
            // "runtime","engine","name","title","url","text",
            List<String> f = Csv.parseLine(line);
            if (f.size() < 5 || f.get(2).isBlank()) continue;
            if (add(people, f.get(4), f.get(2), f.get(3), "CrossLinked/" + f.get(1))) added++;
        }
        return added;
    }

    private int searchDuckDuckGo(String company, Map<String, Map<String, Object>> people, List<String> blocked) {
        int added = 0;
        for (String variant : VARIANTS) {
            String q = "site:linkedin.com/in \"" + company + "\"" + (variant.isEmpty() ? "" : " " + variant);
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create("https://html.duckduckgo.com/html/"))
                        .timeout(Duration.ofSeconds(15))
                        .header("User-Agent", HttpSupport.USER_AGENT)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString("q=" + URLEncoder.encode(q, StandardCharsets.UTF_8)))
                        .build();
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                boolean botCheck = resp.body().contains("anomaly") && !resp.body().contains("result__a");
                if (resp.statusCode() != 200 || botCheck) { // keep what we have
                    blocked.add("DuckDuckGo (bot check)");
                    break;
                }
                Matcher m = RESULT.matcher(resp.body());
                int before = added;
                while (m.find()) {
                    String text = unescape(m.group(2));
                    int dash = text.indexOf(" - ");
                    String name = dash > 0 ? text.substring(0, dash) : text;
                    String title = dash > 0 ? text.substring(dash + 3).replaceAll("\\s*\\|\\s*LinkedIn\\s*$", "") : "";
                    if (add(people, m.group(1), name, title, "DuckDuckGo")) added++;
                }
                if (variant.isEmpty() && added == before) break; // nothing for the base query - variants won't help
                Thread.sleep(1200); // be polite
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                break;
            }
        }
        return added;
    }

    private static boolean add(Map<String, Map<String, Object>> people, String url, String name, String title, String source) {
        String key = url.toLowerCase(Locale.ROOT).replaceFirst("https?://[a-z]{2,3}\\.", "https://www.").replaceAll("/$", "");
        if (people.containsKey(key)) return false;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name.trim());
        row.put("title", title.trim());
        row.put("url", url);
        row.put("source", source);
        people.put(key, row);
        return true;
    }

    private static String unescape(String s) {
        return s.replace("&amp;", "&").replace("&#x27;", "'").replace("&#39;", "'").replace("&quot;", "\"")
                .replace("&lt;", "<").replace("&gt;", ">").replaceAll("[\\u200e\\u200f]", "");
    }
}
