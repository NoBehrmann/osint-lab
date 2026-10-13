package com.osintlab.tools.files;

import com.fasterxml.jackson.databind.JsonNode;
import com.osintlab.support.PythonTools;
import com.osintlab.support.UploadStore;
import com.osintlab.tools.FileTool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Guesses where a photo was taken from its <em>visual content</em> (architecture, vegetation,
 * signage, road markings) using GeoCLIP (scripts/geolocate.py). Unlike the EXIF tool this needs no
 * GPS metadata, so it still works on stripped social-media images. The estimate is coarse (often
 * region/country level) and is only a guess; corroborate it with EXIF GPS and reverse image search.
 *
 * <p>The model runs locally in the Python venv, nothing leaves the machine. The weights (~1-2 GB)
 * download once into the configured cache; run the warm-up in setup-python-tools.cmd so the first
 * real lookup is not gated on that download.
 */
@Component
public class GeolocateTool implements FileTool {

    private final PythonTools python;
    private final Path hfCache;

    public GeolocateTool(PythonTools python, @Value("${osintlab.hf-cache:../.tools/hf-cache}") String hfCache) {
        this.python = python;
        this.hfCache = Path.of(hfCache).toAbsolutePath().normalize();
    }

    @Override
    public String id() {
        return "geolocate";
    }

    @Override
    public int maxConcurrent() {
        return 1; // CPU-heavy model inference, run one at a time
    }

    @Override
    public Object runFile(UploadStore.Upload file) throws Exception {
        // Keep every torch/HF cache under ..\.tools with fixed names. Without an explicit
        // TORCHINDUCTOR_CACHE_DIR, torch writes a "torchinductor_<username>" folder into TEMP.
        Path inductor = Files.createDirectories(hfCache.resolve("inductor"));
        Map<String, String> env = Map.of(
                "HF_HOME", hfCache.toString(),
                "TORCH_HOME", hfCache.toString(),
                "TORCHINDUCTOR_CACHE_DIR", inductor.toString());
        // the upload is already on disk (the upload store owns and deletes it)
        JsonNode result = python.runScript("geolocate.py", List.of("--", file.path().toString()), env,
                Duration.ofSeconds(180));
        return decorate(result);
    }

    /** Turns the raw predictions into rows with map links and a prominent best guess. */
    private static Map<String, Object> decorate(JsonNode result) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode p : result.path("predictions")) {
            double lat = p.path("lat").asDouble();
            double lon = p.path("lon").asDouble();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("lat", lat);
            row.put("lon", lon);
            row.put("confidence", p.path("confidence").asDouble());
            row.put("map", "https://www.openstreetmap.org/?mlat=" + lat + "&mlon=" + lon + "#map=6/" + lat + "/" + lon);
            rows.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        if (!rows.isEmpty()) out.put("bestGuess", rows.get(0));
        out.put("predictions", rows);
        out.put("note", "Guessed from image content, not GPS metadata. Treat it as a lead, not proof. Accuracy is "
                + "often only region/country level. Cross-check with EXIF GPS and reverse image search.");
        return out;
    }
}
