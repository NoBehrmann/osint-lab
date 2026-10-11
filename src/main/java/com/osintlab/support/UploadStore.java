package com.osintlab.support;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Holds uploaded files on disk so several tools can analyse the same upload without the browser
 * sending it once per tool, and without ever loading it into memory (a 500 MB video stays a file).
 * <ul>
 *   <li>Each upload lives in {@code <temp-dir>/uploads/<random id>/upload.<ext>} - the user's file
 *       name is only kept for display, never used in a path or a command line.</li>
 *   <li>Uploads are deleted after {@code osintlab.uploads.keep-minutes}; the oldest are deleted early
 *       when all uploads together exceed {@code osintlab.uploads.max-total-mb}.</li>
 *   <li>Leftovers from a previous run are removed at startup.</li>
 * </ul>
 */
@Component
public class UploadStore {

    public record Upload(String id, String name, long size, Path path, Instant created) {
    }

    private final Path dir;
    private final long maxTotalBytes;
    private final Duration keep;
    private final Map<String, Upload> uploads = new ConcurrentHashMap<>();

    public UploadStore(@Value("${osintlab.temp-dir:../.tmp}") String tempDir,
                       @Value("${osintlab.uploads.max-total-mb:3072}") long maxTotalMb,
                       @Value("${osintlab.uploads.keep-minutes:30}") long keepMinutes) throws IOException {
        this.dir = Path.of(tempDir).toAbsolutePath().normalize().resolve("uploads");
        this.maxTotalBytes = maxTotalMb * 1024 * 1024;
        this.keep = Duration.ofMinutes(keepMinutes);
        deleteRecursively(dir); // uploads don't survive a restart
        Files.createDirectories(dir);
    }

    /** Streams the upload to disk (Tomcat has already spooled it to a temp file - no copy in memory). */
    public synchronized Upload save(MultipartFile file) throws IOException {
        expire();
        String id = UUID.randomUUID().toString();
        Path folder = Files.createDirectories(dir.resolve(id));
        Path target = folder.resolve("upload" + extension(file.getOriginalFilename()));
        try {
            file.transferTo(target.toFile());
        } catch (IOException | RuntimeException e) {
            deleteRecursively(folder);
            throw e;
        }
        Upload upload = new Upload(id, displayName(file.getOriginalFilename()), Files.size(target), target, Instant.now());
        uploads.put(id, upload);
        evictOverCap(id);
        return upload;
    }

    public Upload get(String id) {
        expire();
        Upload u = id == null ? null : uploads.get(id);
        if (u == null || !Files.isRegularFile(u.path())) {
            throw new IllegalArgumentException("Upload not found or expired - choose the file again and re-run");
        }
        return u;
    }

    public void delete(String id) {
        Upload u = uploads.remove(id);
        if (u != null) deleteRecursively(u.path().getParent());
    }

    private void expire() {
        Instant cutoff = Instant.now().minus(keep);
        uploads.values().stream().filter(u -> u.created().isBefore(cutoff)).map(Upload::id).toList().forEach(this::delete);
    }

    /** Deletes the oldest uploads (never the one just saved) until the total fits the cap. */
    private void evictOverCap(String keepId) {
        long total = uploads.values().stream().mapToLong(Upload::size).sum();
        for (Upload u : uploads.values().stream().sorted(Comparator.comparing(Upload::created)).toList()) {
            if (total <= maxTotalBytes) break;
            if (u.id().equals(keepId)) continue;
            delete(u.id());
            total -= u.size();
        }
    }

    /** ".jpg", ".mp4"... - only short alphanumeric extensions survive; tools like ExifTool use it as a hint. */
    private static String extension(String name) {
        if (name == null) return ".bin";
        String n = name.toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        String ext = dot >= 0 ? n.substring(dot + 1) : "";
        return ext.matches("[a-z0-9]{1,8}") ? "." + ext : ".bin";
    }

    private static String displayName(String name) {
        if (name == null || name.isBlank()) return "upload";
        String n = name.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).replaceAll("\\p{Cntrl}", "");
        return n.length() > 200 ? n.substring(0, 200) : n;
    }

    private static void deleteRecursively(Path path) {
        if (!Files.exists(path)) return;
        try (Stream<Path> paths = Files.walk(path)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // best effort - expired uploads are retried on the next cleanup
        }
    }
}
