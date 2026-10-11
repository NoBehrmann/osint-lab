package com.osintlab.tools;

import java.time.Duration;

/**
 * A runnable tool. Implement this as a Spring bean and the matching catalog entry in
 * {@link ToolRegistry} becomes "ready" automatically.
 *
 * <p>Implementations must validate {@code target} themselves (see {@code Inputs}) and throw
 * {@link IllegalArgumentException} for bad input - never pass unchecked input to an external
 * process or service.
 */
public interface OsintTool {

    /** Must match a catalog id in {@link ToolRegistry}. */
    String id();

    /** Runs the tool against the raw target string; the result is serialised to JSON. */
    Object run(String target) throws Exception;

    /** How many runs of this tool may execute at once. Lower it for heavy or rate-limited tools. */
    default int maxConcurrent() {
        return 4;
    }

    /**
     * How long a result is reused for the same target. Raise it (e.g. 24h) for slow-changing,
     * rate-limited sources like WHOIS or Wayback; {@link Duration#ZERO} disables caching.
     * Users can always bypass the cache with "refresh".
     */
    default Duration cacheTtl() {
        return Duration.ofHours(1);
    }
}
