package com.osintlab.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.osintlab.support.PythonTools;
import com.osintlab.support.UploadStore;
import com.osintlab.support.UpstreamException;
import com.osintlab.tools.ToolExecutor;
import com.osintlab.tools.ToolInfo;
import com.osintlab.tools.ToolRegistry;
import com.osintlab.tools.accounts.InstagramSession;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ApiController {

    private static final int MAX_TARGET_LENGTH = 500;

    private final ToolRegistry registry;
    private final ToolExecutor executor;
    private final PythonTools python;
    private final InstagramSession instagram;
    private final UploadStore uploads;

    public ApiController(ToolRegistry registry, ToolExecutor executor, PythonTools python, InstagramSession instagram,
                         UploadStore uploads) {
        this.registry = registry;
        this.executor = executor;
        this.python = python;
        this.instagram = instagram;
        this.uploads = uploads;
    }

    @GetMapping("/tools")
    public List<ToolInfo> tools() {
        return registry.all();
    }

    /**
     * Runs one tool against a target. The frontend calls this once per checked tool, in parallel.
     * {@code fresh=true} bypasses the cache. A cached response carries {@code X-Cached-At} (ISO-8601).
     */
    @PostMapping("/run/{toolId}")
    public ResponseEntity<Object> run(@PathVariable String toolId, @RequestParam String target,
                                      @RequestParam(defaultValue = "false") boolean fresh) throws Exception {
        if (target.isBlank()) throw new IllegalArgumentException("Missing target");
        if (target.length() > MAX_TARGET_LENGTH) throw new IllegalArgumentException("Target too long");

        ToolExecutor.Result result = executor.run(toolId, target, fresh);
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (result.cachedAt() != null) response.header("X-Cached-At", result.cachedAt().toString());
        return response.body(result.value());
    }

    /**
     * Stores an uploaded file once (streamed to disk, never held in memory) so every selected file
     * tool can analyse it via {@link #runFile}. Uploads expire after a while, see {@link UploadStore}.
     */
    @PostMapping(value = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file) throws Exception {
        if (file.isEmpty()) throw new IllegalArgumentException("Empty file");
        UploadStore.Upload u = uploads.save(file);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", u.id());
        out.put("name", u.name());
        out.put("size", u.size());
        return out;
    }

    /** Runs a file tool (metadata, geolocation...) on a stored upload. */
    @PostMapping("/file/{toolId}")
    public Object runFile(@PathVariable String toolId, @RequestParam String id) throws Exception {
        return executor.runFile(toolId, uploads.get(id));
    }

    /** Frees an upload early (the page calls this when a new file replaces it). */
    @DeleteMapping("/files/{id}")
    public ResponseEntity<Void> deleteFile(@PathVariable String id) {
        uploads.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Whether an Instagram sessionid is set, and where it came from. Never returns the token. */
    @GetMapping("/instagram/status")
    public Map<String, Object> instagramStatus() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("configured", instagram.isConfigured());
        out.put("source", instagram.source());
        return out;
    }

    /**
     * Reads the instagram.com sessionid from the user's own browser (scripts/ig_cookie.py) and
     * keeps it in server memory. The token itself is never returned to the browser, only whether
     * it loaded and from which browser.
     */
    @PostMapping("/instagram/load-session")
    public Map<String, Object> loadInstagramSession() throws Exception {
        JsonNode result;
        try {
            result = python.runScript("ig_cookie.py", List.of(), Map.of(), Duration.ofSeconds(30));
        } catch (UpstreamException e) {
            return Map.of("loaded", false, "message", e.getMessage());
        }
        String sessionId = result.path("sessionid").asText("");
        if (sessionId.isBlank()) return Map.of("loaded", false, "message", "No sessionid found in the browser");
        String source = result.path("source").asText("browser");
        instagram.set(sessionId, source);
        return Map.of("loaded", true, "source", source);
    }
}
