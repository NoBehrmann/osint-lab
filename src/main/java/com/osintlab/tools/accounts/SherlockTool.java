package com.osintlab.tools.accounts;

import com.osintlab.support.Csv;
import com.osintlab.support.ProcessRunner;
import com.osintlab.support.PythonTools;
import com.osintlab.support.UpstreamException;
import com.osintlab.tools.OsintTool;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Runs the Sherlock CLI (https://github.com/sherlock-project/sherlock) and parses its CSV report.
 * Sherlock fetches its latest site list (data.json) from GitHub on every run. An email target is
 * searched by its local part.
 */
@Component
public class SherlockTool implements OsintTool {

    /** Per-site HTTP timeout passed to Sherlock. */
    private static final int SITE_TIMEOUT_SECONDS = 15;
    private static final Duration RUN_TIMEOUT = Duration.ofMinutes(4);

    private final ProcessRunner runner;
    private final PythonTools python;

    public SherlockTool(ProcessRunner runner, PythonTools python) {
        this.runner = runner;
        this.python = python;
    }

    @Override
    public String id() {
        return "sherlock";
    }

    @Override
    public int maxConcurrent() {
        return 1; // ~400 HTTP requests per run
    }

    @Override
    public Duration cacheTtl() {
        return Duration.ofHours(6);
    }

    @Override
    public Object run(String target) throws Exception {
        UsernameTarget t = UsernameTarget.of(target);
        String username = t.username();
        String derivedFrom = t.fromEmail();

        List<String> command = List.of(
                python.executable("sherlock"),
                "--no-color", "--csv", "--print-all",
                "--timeout", String.valueOf(SITE_TIMEOUT_SECONDS),
                "--folderoutput", ".",
                "--", username);
        return runner.run(command, RUN_TIMEOUT, result -> parse(username, derivedFrom, result));
    }

    private static Map<String, Object> parse(String username, String derivedFrom, ProcessRunner.Result result)
            throws Exception {
        Path csv = result.workDir().resolve(username + ".csv");
        if (!Files.exists(csv)) {
            throw new UpstreamException("Sherlock produced no report (exit " + result.exitCode() + "): " + result.tail());
        }

        List<Map<String, Object>> found = new ArrayList<>();
        List<Map<String, Object>> inconclusive = new ArrayList<>();
        Map<String, Integer> summary = new TreeMap<>();

        List<Map<String, String>> rows = Csv.readWithHeader(csv);
        for (Map<String, String> f : rows) {
            String site = f.get("name"), url = f.get("url_user"), status = f.get("exists"), http = f.get("http_status");
            summary.merge(status, 1, Integer::sum);

            if ("Claimed".equals(status)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("site", site);
                row.put("url", url);
                found.add(row);
            } else if ("Unknown".equals(status) || "WAF".equals(status)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("site", site);
                row.put("status", "WAF".equals(status) ? "blocked by WAF" : "error / unclear");
                row.put("http", http);
                inconclusive.add(row);
            }
        }
        found.sort(Comparator.comparing(r -> ((String) r.get("site")).toLowerCase()));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("username", username);
        if (derivedFrom != null) out.put("derivedFrom", derivedFrom);
        out.put("sitesChecked", rows.size());
        out.put("found", found.size());
        out.put("accounts", found);
        out.put("summary", summary);
        out.put("inconclusive", inconclusive);
        out.put("note", "Matches are based on HTTP responses and can include false positives - open the link to confirm.");
        return out;
    }
}
