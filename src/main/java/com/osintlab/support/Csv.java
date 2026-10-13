package com.osintlab.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal CSV reading for tool reports (no multi-line fields). */
public final class Csv {

    private Csv() {
    }

    /** Reads a CSV with a header row into one map per line (header -> value). */
    public static List<Map<String, String>> readWithHeader(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<Map<String, String>> rows = new ArrayList<>();
        if (lines.isEmpty()) return rows;
        List<String> header = parseLine(lines.getFirst().replace("\uFEFF", "")); // strip a UTF-8 BOM
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) continue;
            List<String> fields = parseLine(line);
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < header.size(); i++) row.put(header.get(i), i < fields.size() ? fields.get(i) : "");
            rows.add(row);
        }
        return rows;
    }

    /** RFC 4180 line parser; tolerant of stray quotes inside unquoted fields. */
    public static List<String> parseLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cur.append(c);
                }
            } else if (c == '"' && cur.isEmpty()) {
                quoted = true;
            } else if (c == ',') {
                fields.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        fields.add(cur.toString());
        return fields;
    }
}
