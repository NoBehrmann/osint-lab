package com.osintlab.tools.accounts;

import com.osintlab.support.Csv;
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
import java.util.stream.Stream;

/**
 * Holehe (https://github.com/megadose/holehe): checks ~120 sites' signup/login flows to see
 * whether an email address is registered. Runs with -NP so no password-reset emails are triggered.
 */
@Component
public class HoleheTool implements OsintTool {

    private final ProcessRunner runner;
    private final PythonTools python;

    public HoleheTool(ProcessRunner runner, PythonTools python) {
        this.runner = runner;
        this.python = python;
    }

    @Override
    public String id() {
        return "holehe";
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
        String email = Inputs.email(target);
        // Note: don't pass -T. Holehe forwards it to httpx as a string, which makes every check fail.
        List<String> command = List.of(python.executable("holehe"),
                "--no-color", "--no-clear", "-NP", "-C", "--", email);
        return runner.run(command, Duration.ofMinutes(3), r -> parse(email, r));
    }

    private static Map<String, Object> parse(String email, ProcessRunner.Result r) throws Exception {
        Path csv;
        try (Stream<Path> files = Files.list(r.workDir())) {
            csv = files.filter(p -> p.getFileName().toString().startsWith("holehe_") && p.toString().endsWith(".csv"))
                    .findFirst()
                    .orElseThrow(() -> new UpstreamException("Holehe produced no report: " + r.tail()));
        }

        List<Map<String, Object>> registered = new ArrayList<>();
        List<String> notRegistered = new ArrayList<>();
        List<String> couldNotCheck = new ArrayList<>();
        for (Map<String, String> row : Csv.readWithHeader(csv)) {
            String domain = row.getOrDefault("domain", row.get("name"));
            if ("True".equals(row.get("rateLimit"))) {
                couldNotCheck.add(domain);
            } else if ("True".equals(row.get("exists"))) {
                Map<String, Object> hit = new LinkedHashMap<>();
                hit.put("site", domain);
                hit.put("url", "https://" + domain);
                hit.put("recoveryEmail", blankToNull(row.get("emailrecovery")));
                hit.put("recoveryPhone", blankToNull(row.get("phoneNumber")));
                hit.put("other", blankToNull(row.get("others")));
                registered.add(hit);
            } else {
                notRegistered.add(domain);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("email", email);
        out.put("sitesChecked", registered.size() + notRegistered.size() + couldNotCheck.size());
        out.put("registeredOn", registered.size());
        out.put("accounts", registered);
        out.put("notRegistered", notRegistered.size());
        out.put("couldNotCheck", couldNotCheck);
        out.put("note", "Sites in 'couldNotCheck' blocked the check or changed their signup flow, so "
                + "their result is unknown, not negative.");
        return out;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() || s.equals("None") ? null : s;
    }
}
