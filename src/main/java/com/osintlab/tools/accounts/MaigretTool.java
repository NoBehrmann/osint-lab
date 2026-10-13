package com.osintlab.tools.accounts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/**
 * Maigret (https://github.com/soxoj/maigret): username search across the top 500 of its
 * ~3000 sites, extracting profile details (name, bio, links, other ids) where possible.
 */
@Component
public class MaigretTool implements OsintTool {

    private final ProcessRunner runner;
    private final PythonTools python;
    private final ObjectMapper mapper;

    public MaigretTool(ProcessRunner runner, PythonTools python, ObjectMapper mapper) {
        this.runner = runner;
        this.python = python;
        this.mapper = mapper;
    }

    @Override
    public String id() {
        return "maigret";
    }

    @Override
    public int maxConcurrent() {
        return 1;
    }

    @Override
    public Duration cacheTtl() {
        return Duration.ofHours(6);
    }

    @Override
    public Object run(String target) throws Exception {
        UsernameTarget t = UsernameTarget.of(target);
        List<String> command = List.of(python.executable("maigret"),
                "--json", "simple", "-fo", ".", "--no-color", "--no-progressbar",
                "--timeout", "15", "--no-recursion", "--top-sites", "500",
                "--", t.username());
        return runner.run(command, Duration.ofMinutes(8), r -> parse(t, r));
    }

    private Map<String, Object> parse(UsernameTarget t, ProcessRunner.Result r) throws Exception {
        Path report = r.workDir().resolve("report_" + t.username() + "_simple.json");
        if (!Files.exists(report)) throw new UpstreamException("Maigret produced no report: " + r.tail());

        List<Map<String, Object>> accounts = new ArrayList<>();
        JsonNode root = mapper.readTree(report.toFile());
        root.properties().forEach(e -> {
            JsonNode site = e.getValue();
            JsonNode status = site.path("status");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("site", e.getKey());
            row.put("url", status.path("url").asText(site.path("url_user").asText()));
            Map<String, String> details = new LinkedHashMap<>();
            status.path("ids").properties().forEach(id -> details.put(id.getKey(), id.getValue().asText()));
            row.put("details", details.isEmpty() ? null : details);
            List<String> tags = new ArrayList<>();
            status.path("tags").forEach(tag -> tags.add(tag.asText()));
            row.put("tags", tags.isEmpty() ? null : String.join(", ", tags));
            accounts.add(row);
        });
        accounts.sort(Comparator.comparing(a -> ((String) a.get("site")).toLowerCase()));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("username", t.username());
        if (t.fromEmail() != null) out.put("derivedFrom", t.fromEmail());
        out.put("sitesChecked", "top 500");
        out.put("found", accounts.size());
        out.put("accounts", accounts);
        out.put("note", "Matches can include false positives, open the link to confirm.");
        return out;
    }
}
