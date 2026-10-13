package com.osintlab.tools.accounts;

import com.osintlab.support.ToolUnavailableException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The user's own Instagram "sessionid" cookie. Instagram rate-limits anonymous API access, so the
 * Instagram tools need it. It can come from secrets.properties or be loaded at runtime from the
 * user's browser ("Load from browser"). The token stays in server memory and is never sent back
 * to the frontend.
 */
@Component
public class InstagramSession {

    private final String configured;
    private volatile String runtime = "";   // loaded from the browser this session
    private volatile String source;         // where the active value came from

    public InstagramSession(@Value("${osintlab.instagram.sessionid:}") String sessionId) {
        this.configured = sessionId.trim();
        if (!configured.isEmpty()) this.source = "secrets.properties";
    }

    /** Store a sessionid read from the browser; replaces any previous runtime value. */
    public void set(String sessionId, String source) {
        this.runtime = sessionId == null ? "" : sessionId.trim();
        this.source = this.runtime.isEmpty() ? (configured.isEmpty() ? null : "secrets.properties") : source;
    }

    public boolean isConfigured() {
        return active() != null;
    }

    /** Where the active sessionid came from, or null if none is set. Never the value itself. */
    public String source() {
        return active() == null ? null : source;
    }

    public String require() {
        String s = active();
        if (s == null) {
            throw new ToolUnavailableException("No Instagram sessionid set. Click \"Load from browser\", or log in "
                    + "to instagram.com, copy the 'sessionid' cookie into osint-lab/secrets.properties and restart. "
                    + "Use a secondary account: Instagram may flag accounts used for lookups.");
        }
        return s;
    }

    private String active() {
        if (!runtime.isEmpty()) return runtime;
        return configured.isEmpty() ? null : configured;
    }
}
