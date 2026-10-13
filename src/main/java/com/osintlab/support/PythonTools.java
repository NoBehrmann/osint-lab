package com.osintlab.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Locates console scripts installed in the tools venv (see setup-python-tools.cmd) and runs
 * the project's own helper scripts in {@code scripts/}.
 */
@Component
public class PythonTools {

    private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");

    private final Path venv;
    private final Path scripts;
    private final ProcessRunner runner;
    private final ObjectMapper mapper;

    public PythonTools(@Value("${osintlab.venv:../.tools/venv}") String venv,
                       @Value("${osintlab.scripts:scripts}") String scripts,
                       ProcessRunner runner, ObjectMapper mapper) {
        this.venv = Path.of(venv).toAbsolutePath().normalize();
        this.scripts = Path.of(scripts).toAbsolutePath().normalize();
        this.runner = runner;
        this.mapper = mapper;
    }

    /** Path of a console script such as "sherlock". */
    public String executable(String name) {
        Path exe = WINDOWS ? venv.resolve("Scripts").resolve(name + ".exe") : venv.resolve("bin").resolve(name);
        if (!Files.isRegularFile(exe)) {
            throw new ToolUnavailableException(name + " is not installed, run setup-python-tools.cmd");
        }
        return exe.toString();
    }

    /**
     * Runs scripts/&lt;script&gt; with the venv's Python and parses its stdout as JSON.
     * A top-level {"error": "..."} is turned into an {@link UpstreamException}.
     */
    public JsonNode runScript(String script, List<String> args, Map<String, String> env, Duration timeout)
            throws Exception {
        Path file = scripts.resolve(script);
        if (!Files.isRegularFile(file)) throw new ToolUnavailableException("Missing helper script " + file);

        List<String> command = new ArrayList<>(List.of(executable("python"), file.toString()));
        command.addAll(args);
        JsonNode result = runner.run(command, env, timeout, r -> {
            String out = r.stdout().strip();
            String last = out.isEmpty() ? "" : out.lines().reduce((a, b) -> b).orElse("");
            if (!last.startsWith("{") && !last.startsWith("[")) {
                throw new UpstreamException(script + " failed: " + r.tail());
            }
            return mapper.readTree(last);
        });
        if (result.has("error")) throw new UpstreamException(result.path("error").asText());
        return result;
    }
}
