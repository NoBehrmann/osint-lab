package com.osintlab.support;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Converts phone numbers to international E.164 form ("+4915112345678"). Numbers entered without a
 * '+' are read as numbers of {@code osintlab.phone.default-region} via libphonenumber (scripts/phone.py),
 * the same way the Phone Lookup tool reads them, and must be valid numbers of that region.
 */
@Component
public class PhoneNumbers {

    private final PythonTools python;
    private final String defaultRegion;

    public PhoneNumbers(PythonTools python, @Value("${osintlab.phone.default-region:DE}") String defaultRegion) {
        this.python = python;
        this.defaultRegion = defaultRegion;
    }

    public String defaultRegion() {
        return defaultRegion;
    }

    /** @param phone output of {@link Inputs#phone}: digits with an optional leading '+' */
    public Optional<String> e164(String phone) throws Exception {
        if (phone.startsWith("+")) return Optional.of(phone);
        try {
            JsonNode parsed = python.runScript("phone.py", List.of("--", phone, defaultRegion), Map.of(),
                    Duration.ofSeconds(30));
            // Guessing the country is only safe for numbers that are valid there; anything else
            // (e.g. "0000000") must be entered with its +country code.
            String e164 = parsed.path("e164").asText("");
            return parsed.path("valid").asBoolean() && e164.startsWith("+") ? Optional.of(e164) : Optional.empty();
        } catch (UpstreamException e) {
            return Optional.empty(); // unparseable for this region
        }
    }
}
