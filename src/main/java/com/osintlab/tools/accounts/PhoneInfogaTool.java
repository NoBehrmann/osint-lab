package com.osintlab.tools.accounts;

import com.osintlab.support.Inputs;
import com.osintlab.support.PhoneNumbers;
import com.osintlab.support.ProcessRunner;
import com.osintlab.support.ToolUnavailableException;
import com.osintlab.support.UpstreamException;
import com.osintlab.tools.OsintTool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PhoneInfoga (the real Go binary under ..\.tools\phoneinfoga): its keyless "googlesearch" scanner
 * builds a set of ready-to-run search-engine footprints for a number - grouped into social media,
 * disposable-number providers, reputation / "who called" sites and people-search sites - plus the
 * "local" libphonenumber facts. The scanners that need API keys (numverify, Google CSE) self-skip.
 *
 * <p>The googlesearch scanner only <em>generates</em> query URLs; it does not query anything, so this
 * tool is offline and fast. The links are starting points - open them to look for the number.
 */
@Component
public class PhoneInfogaTool implements OsintTool {

    private final ProcessRunner runner;
    private final PhoneNumbers numbers;
    private final Path binary;

    public PhoneInfogaTool(ProcessRunner runner, PhoneNumbers numbers,
                           @Value("${osintlab.phoneinfoga:../.tools/phoneinfoga/phoneinfoga.exe}") String binary) {
        this.runner = runner;
        this.numbers = numbers;
        this.binary = Path.of(binary).toAbsolutePath().normalize();
    }

    @Override
    public String id() {
        return "phoneinfoga";
    }

    @Override
    public Duration cacheTtl() {
        return Duration.ofDays(7); // deterministic URL generation - only changes with the binary
    }

    @Override
    public Object run(String target) throws Exception {
        String input = Inputs.phone(target);
        if (!Files.isRegularFile(binary)) {
            throw new ToolUnavailableException("PhoneInfoga is not installed - expected the binary at " + binary);
        }
        // PhoneInfoga only understands international numbers - a national "0151..." is converted first.
        String phone = numbers.e164(input).orElseThrow(() -> new IllegalArgumentException("Could not read "
                + input + " as a " + numbers.defaultRegion() + " number - enter it with its country code, "
                + "e.g. +4915112345678"));

        List<String> command = List.of(binary.toString(), "scan", "-n", phone);
        return runner.run(command, Duration.ofSeconds(30), r -> {
            if (r.stdout().isBlank()) throw new UpstreamException("PhoneInfoga produced no output: " + r.tail());
            return parse(phone, r.stdout());
        });
    }

    /**
     * PhoneInfoga prints blocks ("Results for &lt;scanner&gt;"); the googlesearch block lists category
     * headers ("Social media:", "Reputation:"...) each followed by tab-indented "URL: ..." lines, and
     * the local block lists "Key: value" facts. We turn that into {footprints, local}.
     */
    private static Map<String, Object> parse(String phone, String stdout) {
        Map<String, List<String>> footprints = new LinkedHashMap<>();
        Map<String, String> local = new LinkedHashMap<>();
        List<String> scanners = new ArrayList<>();

        String scanner = "";
        String category = null;
        for (String raw : stdout.lines().toList()) {
            String line = raw.strip();
            if (line.isEmpty()) continue;

            if (line.startsWith("Results for ")) {
                scanner = line.substring("Results for ".length()).strip();
                scanners.add(scanner);
                category = null;
            } else if ("googlesearch".equals(scanner)) {
                String url = afterUrl(line);
                if (url != null) {
                    if (category != null) footprints.computeIfAbsent(category, k -> new ArrayList<>()).add(url);
                } else if (line.endsWith(":")) {
                    category = line.substring(0, line.length() - 1).strip();
                }
            } else if ("local".equals(scanner)) {
                int colon = line.indexOf(':');
                if (colon > 0) local.put(line.substring(0, colon).strip(), line.substring(colon + 1).strip());
            }
        }

        footprints.values().removeIf(List::isEmpty);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("number", phone);
        out.put("footprints", footprints);
        if (!local.isEmpty()) out.put("local", local);
        out.put("scanners", scanners);
        out.put("note", "Footprints are ready-made search queries, not confirmed hits - open each to look for the "
                + "number. Scanners needing API keys (numverify, Google CSE) are skipped.");
        return out;
    }

    /** Returns the URL from a "URL: https://..." line, or null if the line is not a URL entry. */
    private static String afterUrl(String line) {
        if (!line.startsWith("URL:")) return null;
        String url = line.substring("URL:".length()).strip();
        return url.isEmpty() ? null : url;
    }
}
