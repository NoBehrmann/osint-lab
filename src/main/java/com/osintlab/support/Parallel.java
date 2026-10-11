package com.osintlab.support;

import java.net.http.HttpTimeoutException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Runs a tool's independent lookups (one per site / data source) at the same time, each on its own
 * virtual thread. A lookup that fails becomes {"error": "..."} in the result instead of failing the
 * whole tool, so one slow or broken site never hides the others.
 */
public final class Parallel {

    private Parallel() {
    }

    /** @return results in the same order as {@code tasks}; a task may return null ("nothing found") */
    public static Map<String, Object> all(Map<String, Callable<Object>> tasks) throws InterruptedException {
        Map<String, Future<Object>> futures = new LinkedHashMap<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            tasks.forEach((key, task) -> futures.put(key, executor.submit(task)));
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, Future<Object>> e : futures.entrySet()) {
                try {
                    out.put(e.getKey(), e.getValue().get());
                } catch (ExecutionException x) {
                    out.put(e.getKey(), Map.of("error", message(x.getCause())));
                }
            }
            return out;
        }
    }

    /** True when {@link #all} turned that entry into an error. */
    public static boolean failed(Object result) {
        return result instanceof Map<?, ?> m && m.size() == 1 && m.containsKey("error");
    }

    private static String message(Throwable t) {
        if (t instanceof HttpTimeoutException) return "timed out";
        if (t instanceof UpstreamException || t instanceof IllegalArgumentException) return t.getMessage();
        String msg = t.getMessage();
        return t.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
    }
}
