package com.osintlab.tools.accounts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.osintlab.support.Inputs;
import com.osintlab.support.PythonTools;
import com.osintlab.tools.OsintTool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Offline phone number analysis with Google's libphonenumber (scripts/phone.py): validity,
 * country, region, line type, original carrier, time zones. Numbers without a '+' prefix are
 * read as numbers of {@code osintlab.phone.default-region}.
 */
@Component
public class PhoneTool implements OsintTool {

    private final PythonTools python;
    private final String defaultRegion;

    public PhoneTool(PythonTools python, @Value("${osintlab.phone.default-region:DE}") String defaultRegion) {
        this.python = python;
        this.defaultRegion = defaultRegion;
    }

    @Override
    public String id() {
        return "phone";
    }

    @Override
    public Duration cacheTtl() {
        return Duration.ofDays(7); // offline data, only changes with library updates
    }

    @Override
    public Object run(String target) throws Exception {
        String phone = Inputs.phone(target);
        JsonNode result = python.runScript("phone.py", List.of("--", phone, defaultRegion), Map.of(), Duration.ofSeconds(30));
        if (result instanceof ObjectNode node) {
            if (!phone.startsWith("+")) node.put("assumedRegion", defaultRegion);
            node.put("note", "Carrier is the original network operator. The number may have been ported since.");
        }
        return result;
    }
}
