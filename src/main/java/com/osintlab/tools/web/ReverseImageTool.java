package com.osintlab.tools.web;

import com.osintlab.support.Inputs;
import com.osintlab.support.UploadStore;
import com.osintlab.tools.FileTool;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reverse image search. For a public image URL it builds direct "search by this URL" links. For an
 * uploaded file it links to each engine's upload page instead - no free reverse-image API returns
 * match data, so the actual lookup is one drag-and-drop away. Makes no requests itself.
 */
@Component
public class ReverseImageTool implements FileTool {

    @Override
    public String id() {
        return "reverse-image";
    }

    @Override
    public Object run(String target) {
        URI uri = Inputs.url(target);
        String u = URLEncoder.encode(uri.toString(), StandardCharsets.UTF_8);
        List<Map<String, String>> links = new ArrayList<>();
        add(links, "Google Lens", "https://lens.google.com/uploadbyurl?url=" + u);
        add(links, "Bing Visual Search", "https://www.bing.com/images/search?view=detailv2&iss=sbi&q=imgurl:" + u);
        add(links, "Yandex Images", "https://yandex.com/images/search?rpt=imageview&url=" + u);
        add(links, "TinEye", "https://tineye.com/search?url=" + u);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("image", uri.toString());
        out.put("searches", links);
        out.put("note", "Works only for publicly reachable image URLs. Yandex is usually best for faces and places.");
        return out;
    }

    @Override
    public Object runFile(UploadStore.Upload file) {
        List<Map<String, String>> links = new ArrayList<>();
        add(links, "Google Lens", "https://lens.google.com/");
        add(links, "Yandex Images", "https://yandex.com/images/");
        add(links, "Bing Visual Search", "https://www.bing.com/visualsearch");
        add(links, "TinEye", "https://tineye.com/");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("file", file.name());
        out.put("searches", links);
        out.put("note", "No free reverse-image API returns match data, so the uploaded file is not sent anywhere - "
                + "open an engine and drop the image in. Yandex is usually best for faces and places.");
        return out;
    }

    private static void add(List<Map<String, String>> links, String engine, String url) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("engine", engine);
        row.put("url", url);
        links.add(row);
    }
}
