package com.osintlab.tools.accounts;

import com.osintlab.support.PythonTools;
import com.osintlab.tools.OsintTool;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Toutatis (https://github.com/megadose/toutatis): Instagram account details including the
 * obfuscated recovery email/phone. Runs through scripts/toutatis_json.py for JSON output; the
 * sessionid is passed via environment variable, not the command line.
 */
@Component
public class ToutatisTool implements OsintTool {

    private final PythonTools python;
    private final InstagramSession session;

    public ToutatisTool(PythonTools python, InstagramSession session) {
        this.python = python;
        this.session = session;
    }

    @Override
    public String id() {
        return "toutatis";
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
        String username = UsernameTarget.of(target).username();
        return python.runScript("toutatis_json.py", List.of("--", username),
                Map.of("IG_SESSIONID", session.require()), Duration.ofSeconds(60));
    }
}
