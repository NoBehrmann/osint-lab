package com.osintlab.tools.files;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.lang.GeoLocation;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.Tag;
import com.drew.metadata.exif.GpsDirectory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osintlab.support.ProcessRunner;
import com.osintlab.support.UploadStore;
import com.osintlab.support.UpstreamException;
import com.osintlab.tools.FileTool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Metadata of an uploaded file plus its hashes (for VirusTotal & co).
 * <ul>
 *   <li>With ExifTool installed ({@code osintlab.exiftool}): photos, videos (MP4/MOV GPS, recording
 *       device, creation time, embedded GPS tracks of dashcams/drones/action cams), PDF and Office
 *       documents (author, company, software, edit time, revision) and ~400 other formats.</li>
 *   <li>Without it: images (and basic MP4/MOV) via the built-in metadata-extractor library.</li>
 * </ul>
 * The file is streamed from disk - large videos never sit in memory.
 */
@Component
public class MetadataTool implements FileTool {

    /** Tags surfaced at the top, in this order - they often identify a device, person, place or time. */
    private static final List<String> HIGHLIGHTS = List.of(
            "FileType", "MIMEType", "ImageSize", "Duration", "VideoFrameRate",
            "Make", "Model", "LensModel", "SerialNumber", "BodySerialNumber", "InternalSerialNumber", "LensSerialNumber",
            "Software", "CreatorTool", "Encoder", "HandlerVendorID", "Producer", "Application", "AppVersion",
            "Author", "Creator", "Artist", "LastModifiedBy", "OwnerName", "Company", "Manager", "Copyright",
            "Title", "Subject", "Description", "ImageDescription", "UserComment", "XPAuthor", "XPComment",
            "HostComputer", "Template", "RevisionNumber", "TotalEditTime", "PageCount", "Pages",
            "DateTimeOriginal", "CreationDate", "CreateDate", "MediaCreateDate", "ModifyDate",
            "HistorySoftwareAgent", "HistoryWhen", "OffsetTimeOriginal", "Rotation");

    /** QuickTime dates are stored in UTC by the spec - worth saying so next to the value. */
    private static final Set<String> UTC_DATES = Set.of("CreateDate", "ModifyDate", "MediaCreateDate", "TrackCreateDate");
    private static final Set<String> VIDEO_TYPES = Set.of("MP4", "MOV", "M4V", "3GP", "3G2", "MTS", "M2TS", "AVI",
            "INSV", "LRV", "360", "MKV", "WEBM", "WMV");
    private static final Pattern TWO_NUMBERS = Pattern.compile("([+-]?\\d+(?:\\.\\d+)?)[ ,]+([+-]?\\d+(?:\\.\\d+)?)");

    private final ProcessRunner runner;
    private final ObjectMapper mapper;
    private final Path exiftool;

    public MetadataTool(ProcessRunner runner, ObjectMapper mapper,
                        @Value("${osintlab.exiftool:../.tools/exiftool/exiftool.exe}") String exiftool) {
        this.runner = runner;
        this.mapper = mapper;
        this.exiftool = Path.of(exiftool).toAbsolutePath().normalize();
    }

    @Override
    public String id() {
        return "exif";
    }

    @Override
    public Object runFile(UploadStore.Upload file) throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("file", file.name());
        out.put("size", humanSize(file.size()));

        Map<String, String> hashes = hashes(file.path());
        out.put("hashes", hashes);
        out.put("virusTotal", "https://www.virustotal.com/gui/file/" + hashes.get("SHA-256"));

        if (Files.isRegularFile(exiftool)) {
            withExifTool(file.path(), out);
        } else {
            out.put("reader", "built-in (images only) - install ExifTool for video, PDF and Office metadata, see README");
            withMetadataExtractor(file.path(), out);
        }
        return out;
    }

    // ----------------------------------------------------------------- ExifTool

    private void withExifTool(Path path, Map<String, Object> out) throws Exception {
        // -G1: "Group:Tag" keys; -c: GPS as signed decimal degrees; System tags would only describe our
        // temp copy (its path and upload time), so they are left out.
        List<String> cmd = List.of(exiftool.toString(), "-j", "-G1", "-a", "-c", "%+.6f",
                "-api", "LargeFileSupport=1", "--System:All", path.toString());
        JsonNode tags = runExifTool(cmd, Duration.ofSeconds(90));

        List<String> notes = new ArrayList<>();
        for (String key : List.of("ExifTool:Error", "ExifTool:Warning")) {
            if (tags.hasNonNull(key)) notes.add(tags.path(key).asText());
        }
        String fileType = tags.path("File:FileType").asText("");

        Object gps = gps(tags);
        out.put("gps", gps == null ? "no GPS data" : gps);

        Map<String, String> highlights = new LinkedHashMap<>();
        for (String wanted : HIGHLIGHTS) {
            tags.properties().forEach(e -> {
                String key = e.getKey();
                String tag = key.substring(key.indexOf(':') + 1);
                if (!tag.equals(wanted) || highlights.containsKey(wanted)) return;
                String value = text(e.getValue());
                if (value.isBlank() || value.equals("0000:00:00 00:00:00")) return;
                if (key.startsWith("QuickTime:") && UTC_DATES.contains(tag)) value += " (UTC)";
                highlights.put(wanted, value);
            });
        }
        out.put("highlights", highlights.isEmpty() ? "no identifying metadata" : highlights);

        if (VIDEO_TYPES.contains(fileType.toUpperCase(Locale.ROOT))) {
            try {
                Object track = gpsTrack(path);
                if (track != null) out.put("videoGpsTrack", track);
            } catch (Exception e) {
                notes.add("GPS track not read: " + e.getMessage());
            }
        }

        Map<String, Map<String, String>> all = new TreeMap<>();
        tags.properties().forEach(e -> {
            String key = e.getKey();
            int colon = key.indexOf(':');
            if (colon < 0 || key.startsWith("ExifTool:")) return; // SourceFile (our temp path), version info
            String value = text(e.getValue());
            if (value.length() > 300) value = value.substring(0, 300) + "...";
            all.computeIfAbsent(key.substring(0, colon), g -> new TreeMap<>()).put(key.substring(colon + 1), value);
        });
        if (!notes.isEmpty()) out.put("notes", notes);
        out.put("reader", "ExifTool");
        out.put("allMetadata", all);
    }

    private static Map<String, Object> gps(JsonNode tags) {
        Double lat = number(tags.path("Composite:GPSLatitude"));
        Double lon = number(tags.path("Composite:GPSLongitude"));
        if (lat == null || lon == null) {
            // QuickTime "GPSCoordinates" / XMP "GPSPosition" style values: "+48.8584, +2.2945, 35"
            for (var it = tags.properties().iterator(); it.hasNext() && lat == null; ) {
                var e = it.next();
                if (!e.getKey().endsWith(":GPSCoordinates") && !e.getKey().endsWith(":GPSPosition")) continue;
                Matcher m = TWO_NUMBERS.matcher(text(e.getValue()));
                if (m.find()) {
                    lat = Double.parseDouble(m.group(1));
                    lon = Double.parseDouble(m.group(2));
                }
            }
        }
        if (lat == null || lon == null || (lat == 0 && lon == 0)) return null;
        Map<String, Object> location = location(lat, lon);
        String alt = text(tags.path("Composite:GPSAltitude"));
        if (!alt.isBlank()) location.put("altitude", alt);
        return location;
    }

    /**
     * Timed GPS samples embedded in the video stream (dashcams, drones, GoPro/Insta360, some phones).
     * Needs -ee, which reads the whole file - seconds to tens of seconds for large videos.
     */
    private Object gpsTrack(Path path) throws Exception {
        List<String> cmd = List.of(exiftool.toString(), "-ee", "-j", "-n", "-G3", "-api", "LargeFileSupport=1",
                "-GPSLatitude", "-GPSLongitude", "-GPSDateTime", "-GPSSpeed", path.toString());
        JsonNode tags = runExifTool(cmd, Duration.ofSeconds(180));

        Map<String, Map<String, String>> docs = new TreeMap<>((a, b) -> docNumber(a) - docNumber(b));
        tags.properties().forEach(e -> {
            String key = e.getKey();
            if (!key.startsWith("Doc")) return; // "Main:" = the file-level position, already shown
            int colon = key.indexOf(':');
            docs.computeIfAbsent(key.substring(0, colon), d -> new LinkedHashMap<>()).put(key.substring(colon + 1), text(e.getValue()));
        });
        List<Map<String, Object>> points = new ArrayList<>();
        for (Map<String, String> d : docs.values()) {
            Double lat = parse(d.get("GPSLatitude")), lon = parse(d.get("GPSLongitude"));
            if (lat == null || lon == null || (lat == 0 && lon == 0)) continue;
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("time", d.getOrDefault("GPSDateTime", ""));
            p.put("latitude", round(lat));
            p.put("longitude", round(lon));
            if (d.containsKey("GPSSpeed")) p.put("speed", d.get("GPSSpeed"));
            points.add(p);
        }
        if (points.isEmpty()) return null;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("points", points.size());
        out.put("start", withMap(points.getFirst()));
        out.put("end", withMap(points.getLast()));
        if (points.size() > 2) {
            int step = Math.max(1, points.size() / 20);
            List<Map<String, Object>> sample = new ArrayList<>();
            for (int i = 0; i < points.size(); i += step) sample.add(points.get(i));
            out.put("samples", sample);
        }
        return out;
    }

    private JsonNode runExifTool(List<String> cmd, Duration timeout) throws Exception {
        return runner.run(cmd, timeout, r -> {
            String stdout = r.stdout().strip();
            if (!stdout.startsWith("[")) throw new UpstreamException("ExifTool failed: " + r.tail());
            JsonNode arr = mapper.readTree(stdout);
            return arr.isArray() && !arr.isEmpty() ? arr.get(0) : mapper.createObjectNode();
        });
    }

    // ------------------------------------------------------ metadata-extractor

    private void withMetadataExtractor(Path path, Map<String, Object> out) throws Exception {
        Metadata metadata;
        try {
            metadata = ImageMetadataReader.readMetadata(path.toFile());
        } catch (ImageProcessingException e) {
            out.put("metadata", "Unsupported file type for the built-in reader - supported: JPEG, PNG, WebP, HEIC, "
                    + "TIFF, RAW, GIF, BMP, MP4/MOV. Install ExifTool for video, PDF and Office files.");
            return;
        }
        GpsDirectory gpsDir = metadata.getFirstDirectoryOfType(GpsDirectory.class);
        GeoLocation geo = gpsDir == null ? null : gpsDir.getGeoLocation();
        out.put("gps", geo != null && !geo.isZero() ? location(geo.getLatitude(), geo.getLongitude()) : "no GPS data");

        Map<String, String> highlights = new LinkedHashMap<>();
        Map<String, Map<String, String>> all = new LinkedHashMap<>();
        for (Directory dir : metadata.getDirectories()) {
            Map<String, String> tags = new LinkedHashMap<>();
            for (Tag tag : dir.getTags()) {
                String value = tag.getDescription();
                if (value == null) continue;
                if (value.length() > 300) value = value.substring(0, 300) + "...";
                tags.put(tag.getTagName(), value);
                String compact = tag.getTagName().replaceAll("[^A-Za-z]", "");
                if (HIGHLIGHTS.contains(compact)) highlights.putIfAbsent(tag.getTagName(), value);
            }
            if (!tags.isEmpty()) {
                String name = dir.getName();
                for (int i = 2; all.containsKey(name); i++) name = dir.getName() + " #" + i;
                all.put(name, tags);
            }
        }
        out.put("highlights", highlights.isEmpty() ? "no identifying metadata" : highlights);
        out.put("allMetadata", all);
    }

    // ----------------------------------------------------------------- helpers

    /** MD5, SHA-1 and SHA-256 in one streamed pass over the file. */
    private static Map<String, String> hashes(Path path) throws Exception {
        MessageDigest md5 = MessageDigest.getInstance("MD5");
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[1 << 16];
        try (InputStream in = Files.newInputStream(path)) {
            for (int n; (n = in.read(buf)) > 0; ) {
                md5.update(buf, 0, n);
                sha1.update(buf, 0, n);
                sha256.update(buf, 0, n);
            }
        }
        Map<String, String> out = new LinkedHashMap<>();
        out.put("MD5", HexFormat.of().formatHex(md5.digest()));
        out.put("SHA-1", HexFormat.of().formatHex(sha1.digest()));
        out.put("SHA-256", HexFormat.of().formatHex(sha256.digest()));
        return out;
    }

    private static Map<String, Object> location(double lat, double lon) {
        double la = round(lat), lo = round(lon);
        Map<String, Object> location = new LinkedHashMap<>();
        location.put("latitude", la);
        location.put("longitude", lo);
        location.put("openStreetMap", "https://www.openstreetmap.org/?mlat=" + la + "&mlon=" + lo + "#map=16/" + la + "/" + lo);
        location.put("googleMaps", "https://www.google.com/maps?q=" + la + "," + lo);
        return location;
    }

    private static Map<String, Object> withMap(Map<String, Object> point) {
        Map<String, Object> p = new LinkedHashMap<>(point);
        p.put("map", "https://www.openstreetmap.org/?mlat=" + point.get("latitude") + "&mlon=" + point.get("longitude")
                + "#map=15/" + point.get("latitude") + "/" + point.get("longitude"));
        return p;
    }

    private static double round(double v) {
        return Math.round(v * 1e6) / 1e6;
    }

    private static Double number(JsonNode v) {
        // not a ternary: "cond ? double : Double" would unbox a null parse() result and throw
        if (v.isNumber()) return v.asDouble();
        return parse(v.asText(null));
    }

    private static Double parse(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int docNumber(String group) {
        try {
            return Integer.parseInt(group.substring(3));
        } catch (RuntimeException e) {
            return Integer.MAX_VALUE;
        }
    }

    private static String text(JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) return "";
        if (v.isArray()) {
            List<String> parts = new ArrayList<>();
            v.forEach(x -> parts.add(x.asText()));
            return String.join(", ", parts);
        }
        return v.asText();
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " bytes";
        String[] units = {"KB", "MB", "GB"};
        double v = bytes;
        int u = -1;
        while (v >= 1024 && u < units.length - 1) {
            v /= 1024;
            u++;
        }
        return String.format(Locale.ROOT, "%.1f %s (%,d bytes)", v, units[u], bytes);
    }
}
