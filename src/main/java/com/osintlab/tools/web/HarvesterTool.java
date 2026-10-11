package com.osintlab.tools.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osintlab.support.Inputs;
import com.osintlab.support.ProcessRunner;
import com.osintlab.support.PythonTools;
import com.osintlab.support.UpstreamException;
import com.osintlab.tools.OsintTool;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * theHarvester (https://github.com/laramies/theHarvester): emails, hosts and IPs for a domain from
 * all of its sources that work without API keys (keyed sources are skipped).
 */
@Component
public class HarvesterTool implements OsintTool {

    private final ProcessRunner runner;
    private final PythonTools python;
    private final ObjectMapper mapper;

    public HarvesterTool(ProcessRunner runner, PythonTools python, ObjectMapper mapper) {
        this.runner = runner;
        this.python = python;
        this.mapper = mapper;
    }

    @Override
    public String id() {
        return "harvester";
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
        String domain = Inputs.domain(target);
        List<String> command = List.of(python.executable("theHarvester"),
                "-d", domain, "-b", "all", "-q", "-f", "report");
        return runner.run(command, Duration.ofMinutes(6), r -> {
            Path report = r.workDir().resolve("report.json");
            if (!Files.exists(report)) throw new UpstreamException("theHarvester produced no report: " + r.tail());
            JsonNode n = mapper.readTree(report.toFile());

            // hosts come as "name" or "name:ip" - group IPs per host
            Map<String, String> hosts = new TreeMap<>();
            for (JsonNode h : n.path("hosts")) {
                String[] parts = h.asText().split(":", 2);
                hosts.merge(parts[0].toLowerCase(), parts.length > 1 ? parts[1] : "",
                        (a, b) -> a.isEmpty() ? b : b.isEmpty() || a.contains(b) ? a : a + ", " + b);
            }
            List<Map<String, String>> hostRows = new ArrayList<>();
            hosts.forEach((name, ip) -> {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("host", name);
                row.put("ip", ip.isEmpty() ? null : ip);
                hostRows.add(row);
            });

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("domain", domain);
            out.put("emails", texts(n.path("emails")));
            out.put("hostCount", hostRows.size());
            out.put("hosts", hostRows);
            out.put("ips", texts(n.path("ips")));
            out.put("asns", texts(n.path("asns")));
            out.put("interestingUrls", texts(n.path("interesting_urls")));
            out.put("people", texts(n.path("linkedin_people")));
            return out;
        });
    }

    private static List<String> texts(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(v -> out.add(v.asText()));
        return out;
    }
}
