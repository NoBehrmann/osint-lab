package com.osintlab.tools.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osintlab.support.Inputs;
import com.osintlab.support.ProcessRunner;
import com.osintlab.support.PythonTools;
import com.osintlab.support.UpstreamException;
import com.osintlab.tools.OsintTool;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * dnstwist (https://github.com/elceef/dnstwist): generates typo/homoglyph/bitsquatting variants
 * of a domain and reports the ones that are registered - typical phishing infrastructure.
 */
@Component
public class DnstwistTool implements OsintTool {

    private final ProcessRunner runner;
    private final PythonTools python;
    private final ObjectMapper mapper;

    public DnstwistTool(ProcessRunner runner, PythonTools python, ObjectMapper mapper) {
        this.runner = runner;
        this.python = python;
        this.mapper = mapper;
    }

    @Override
    public String id() {
        return "dnstwist";
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
        List<String> command = List.of(python.executable("dnstwist"),
                "--registered", "--format", "json", "--threads", "40", "--", domain);
        return runner.run(command, Duration.ofMinutes(6), r -> {
            String json = r.stdout().strip();
            if (!json.startsWith("[")) throw new UpstreamException("dnstwist failed: " + r.tail());

            List<Map<String, Object>> rows = new ArrayList<>();
            for (JsonNode n : mapper.readTree(json)) {
                if (n.path("fuzzer").asText().contains("original")) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("domain", n.path("domain").asText());
                row.put("technique", n.path("fuzzer").asText());
                row.put("ip", join(n.path("dns_a")));
                row.put("mx", join(n.path("dns_mx")));
                row.put("ns", join(n.path("dns_ns")));
                rows.add(row);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("domain", domain);
            out.put("registeredLookalikes", rows.size());
            out.put("lookalikes", rows);
            out.put("note", "Registered does not mean malicious - check entries with MX records or recent "
                    + "registration first.");
            return out;
        });
    }

    private static String join(JsonNode arr) {
        List<String> parts = new ArrayList<>();
        arr.forEach(v -> {
            String s = v.asText();
            if (!s.isBlank()) parts.add(s.startsWith("!") ? "(DNS " + s.substring(1) + ")" : s); // e.g. !ServFail
        });
        return parts.isEmpty() ? null : String.join(", ", parts);
    }
}
