package com.osintlab.tools;

import com.osintlab.support.UploadStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Runs tools with protection against overload:
 * <ul>
 *   <li>a global cap on concurrent tool runs,</li>
 *   <li>a per-tool cap ({@link OsintTool#maxConcurrent()}),</li>
 *   <li>a short-lived result cache so repeated searches don't hit upstream services again.</li>
 * </ul>
 * Callers that can't get a slot within {@link #WAIT_SECONDS} get a {@link ToolBusyException}.
 */
@Service
public class ToolExecutor {

    static final int GLOBAL_LIMIT = 16;
    static final long WAIT_SECONDS = 30;
    private static final int CACHE_MAX_ENTRIES = 500;

    /** @param cachedAt when the value was produced, or null if it was produced by this call */
    public record Result(Object value, Instant cachedAt) {
    }

    private record CacheEntry(Object value, Instant created, Instant expires) {
    }

    private final ToolRegistry registry;
    private final Semaphore global = new Semaphore(GLOBAL_LIMIT, true);
    private final Map<String, Semaphore> perTool = new ConcurrentHashMap<>();
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public ToolExecutor(ToolRegistry registry) {
        this.registry = registry;
    }

    /** @param fresh skip the cache lookup (the new result is still cached) */
    public Result run(String toolId, String target, boolean fresh) throws Exception {
        OsintTool tool = registry.get(toolId);
        String key = toolId + "|" + target.trim().toLowerCase(Locale.ROOT);

        CacheEntry hit = fresh ? null : cache.get(key);
        if (hit != null && hit.expires().isAfter(Instant.now())) {
            return new Result(hit.value(), hit.created());
        }

        Object value = limited(tool, () -> tool.run(target));
        if (!tool.cacheTtl().isZero()) {
            Instant now = Instant.now();
            putCache(key, new CacheEntry(value, now, now.plus(tool.cacheTtl())));
        }
        return new Result(value, null);
    }

    /** Runs a file tool on an upload. Not cached - uploads are processed and forgotten. */
    public Object runFile(String toolId, UploadStore.Upload file) throws Exception {
        if (!(registry.get(toolId) instanceof FileTool tool)) {
            throw new IllegalArgumentException(toolId + " does not accept file uploads");
        }
        return limited(tool, () -> tool.runFile(file));
    }

    @FunctionalInterface
    private interface Work {
        Object call() throws Exception;
    }

    private Object limited(OsintTool tool, Work work) throws Exception {
        Semaphore toolSlots = perTool.computeIfAbsent(tool.id(), id -> new Semaphore(tool.maxConcurrent(), true));
        acquire(global, "Server busy - too many tools running, try again shortly");
        try {
            acquire(toolSlots, tool.id() + " is busy - try again shortly");
            try {
                return work.call();
            } finally {
                toolSlots.release();
            }
        } finally {
            global.release();
        }
    }

    private static void acquire(Semaphore semaphore, String busyMessage) throws InterruptedException {
        if (!semaphore.tryAcquire(WAIT_SECONDS, TimeUnit.SECONDS)) {
            throw new ToolBusyException(busyMessage);
        }
    }

    private void putCache(String key, CacheEntry entry) {
        if (cache.size() >= CACHE_MAX_ENTRIES) {
            Instant now = Instant.now();
            cache.values().removeIf(e -> e.expires().isBefore(now));
            if (cache.size() >= CACHE_MAX_ENTRIES) cache.clear();
        }
        cache.put(key, entry);
    }
}
