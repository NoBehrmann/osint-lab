package com.osintlab.support;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/**
 * Runs external tools (e.g. Python CLIs) safely:
 * <ul>
 *   <li>no shell - arguments are passed as a list, so they can't be interpreted as shell syntax,</li>
 *   <li>each run gets its own scratch directory under the project's temp dir, deleted afterwards,</li>
 *   <li>a hard timeout that kills the whole process tree.</li>
 * </ul>
 */
@Component
public class ProcessRunner {

    private static final int MAX_OUTPUT_BYTES = 1_000_000;

    public record Result(int exitCode, String stdout, String stderr, Path workDir) {

        /** Last few lines of stderr (or stdout) for error messages. */
        public String tail() {
            String text = stderr.isBlank() ? stdout : stderr;
            List<String> lines = text.lines().filter(l -> !l.isBlank()).toList();
            return String.join(" | ", lines.subList(Math.max(0, lines.size() - 3), lines.size()));
        }
    }

    @FunctionalInterface
    public interface Handler<T> {
        /** Reads results while the scratch directory still exists. */
        T handle(Result result) throws Exception;
    }

    private final Path runsDir;

    public ProcessRunner(@Value("${osintlab.temp-dir:../.tmp}") String tempDir) {
        this.runsDir = Path.of(tempDir).toAbsolutePath().normalize().resolve("runs");
    }

    public <T> T run(List<String> command, Duration timeout, Handler<T> handler) throws Exception {
        return run(command, Map.of(), timeout, handler);
    }

    /** @param extraEnv extra environment variables - use these for secrets instead of arguments */
    public <T> T run(List<String> command, Map<String, String> extraEnv, Duration timeout, Handler<T> handler)
            throws Exception {
        Files.createDirectories(runsDir);
        Path work = Files.createTempDirectory(runsDir, "run-");
        try {
            Path out = work.resolve("_stdout.txt");
            Path err = work.resolve("_stderr.txt");
            ProcessBuilder pb = new ProcessBuilder(command)
                    .directory(work.toFile())
                    .redirectOutput(out.toFile())
                    .redirectError(err.toFile());
            Map<String, String> env = pb.environment();
            env.put("PYTHONUTF8", "1");
            env.put("PYTHONIOENCODING", "utf-8");
            env.put("NO_COLOR", "1");
            env.put("TEMP", work.toString());
            env.put("TMP", work.toString());
            env.putAll(extraEnv);

            Process process = pb.start();
            try {
                if (!process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    kill(process);
                    throw new TimeoutException("Tool did not finish within " + timeout.toSeconds() + " s");
                }
            } catch (InterruptedException e) {
                kill(process);
                throw e;
            }
            return handler.handle(new Result(process.exitValue(), read(out), read(err), work));
        } finally {
            deleteQuietly(work);
        }
    }

    private static void kill(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static String read(Path file) throws IOException {
        if (!Files.exists(file)) return "";
        try (InputStream in = Files.newInputStream(file)) {
            return new String(in.readNBytes(MAX_OUTPUT_BYTES), StandardCharsets.UTF_8);
        }
    }

    private static void deleteQuietly(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // best effort - leftover scratch dirs are harmless
        }
    }
}
