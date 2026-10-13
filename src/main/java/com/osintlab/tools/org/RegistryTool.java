package com.osintlab.tools.org;

import com.fasterxml.jackson.databind.JsonNode;
import com.osintlab.support.HttpSupport;
import com.osintlab.support.Inputs;
import com.osintlab.support.Parallel;
import com.osintlab.tools.OsintTool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

/**
 * Official company registries with free keyless APIs:
 * <ul>
 *   <li>GLEIF, the global Legal Entity Identifier (LEI) index: legal name, registry number, addresses,
 *       status and the ownership tree (direct / ultimate parent, number of subsidiaries).</li>
 *   <li>SEC EDGAR, US filers: industry, state of incorporation, former names, latest filings.</li>
 *   <li>EU VIES: an EU VAT number's validity and registered name/address.</li>
 * </ul>
 * Accepts a company name, an LEI (20 characters) or an EU VAT number such as IE6388047V.
 */
@Component
public class RegistryTool implements OsintTool {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final String GLEIF = "https://api.gleif.org/api/v1/";
    private static final Pattern LEI = Pattern.compile("^[A-Z0-9]{18}[0-9]{2}$");
    private static final Pattern VAT = Pattern.compile(
            "^(AT|BE|BG|CY|CZ|DE|DK|EE|EL|GR|ES|FI|FR|HR|HU|IE|IT|LT|LU|LV|MT|NL|PL|PT|RO|SE|SI|SK|XI)([0-9A-Z]{2,12})$");

    private final HttpSupport http;
    private final Map<String, String> secHeaders;

    public RegistryTool(HttpSupport http, @Value("${osintlab.sec.contact:osint-lab@example.com}") String contact) {
        this.http = http;
        // SEC rejects requests whose User-Agent has no contact address (fair-access policy)
        this.secHeaders = Map.of("User-Agent", "OSINT-Lab research tool " + contact);
    }

    @Override
    public String id() {
        return "registry";
    }

    @Override
    public Duration cacheTtl() {
        return Duration.ofHours(24);
    }

    @Override
    public Object run(String target) throws Exception {
        String input = Inputs.company(target);
        String compact = input.replaceAll("[\\s.\\-]", "").toUpperCase(Locale.ROOT);

        Map<String, Object> out = new LinkedHashMap<>();
        if (LEI.matcher(compact).matches()) {
            out.put("lei", compact);
            JsonNode rec = http.getJsonOrNull(GLEIF + "lei-records/" + compact, TIMEOUT);
            if (rec != null) {
                out.put("GLEIF", details(rec.path("data")));
                return out;
            }
            out.put("GLEIF", "no LEI record " + compact + ", searching it as a name instead");
        }

        var vat = VAT.matcher(compact);
        String name = input;
        if (vat.matches() && vat.group(2).chars().filter(Character::isDigit).count() >= 2) {
            Map<String, Object> vies = vies(vat.group(1).equals("GR") ? "EL" : vat.group(1), vat.group(2));
            out.put("EU VAT (VIES)", vies);
            if (!(vies.get("name") instanceof String registered)) return out;
            name = registered; // also look the registered company up by name
        }

        String q = name;
        Map<String, Callable<Object>> tasks = new LinkedHashMap<>();
        tasks.put("GLEIF", () -> gleifSearch(q));
        tasks.put("SEC EDGAR (US filers)", () -> edgar(q));
        out.put("searchedName", q);
        out.putAll(Parallel.all(tasks));
        out.put("note", "Detailed records are for the FIRST match. If it's the wrong company, search its exact "
                + "legal name or its LEI.");
        return out;
    }

    // ------------------------------------------------------------------- GLEIF

    private Object gleifSearch(String name) throws Exception {
        JsonNode r = http.getJsonOrNull(GLEIF + "lei-records?filter%5Bentity.legalName%5D=" + enc(name)
                + "&page%5Bsize%5D=8", TIMEOUT);
        JsonNode data = r == null ? null : r.path("data");
        if (data == null || data.isEmpty()) return "no LEI-registered entity with that name";

        List<Map<String, Object>> matches = new ArrayList<>();
        for (JsonNode d : data) {
            JsonNode e = d.path("attributes").path("entity");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", e.path("legalName").path("name").asText());
            row.put("country", e.path("legalAddress").path("country").asText());
            row.put("city", e.path("legalAddress").path("city").asText());
            row.put("status", e.path("status").asText());
            row.put("lei", d.path("id").asText());
            matches.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("matches", r.path("meta").path("pagination").path("total").asInt());
        out.put("topMatches", matches);
        out.put("firstMatch", details(data.get(0)));
        return out;
    }

    /** Full record of one LEI plus its ownership links. */
    private Map<String, Object> details(JsonNode rec) throws Exception {
        String lei = rec.path("id").asText();
        JsonNode a = rec.path("attributes");
        JsonNode e = a.path("entity");

        Map<String, Callable<Object>> tasks = new LinkedHashMap<>();
        tasks.put("directParent", () -> parent(lei, "direct-parent"));
        tasks.put("ultimateParent", () -> parent(lei, "ultimate-parent"));
        tasks.put("subsidiaries", () -> children(lei));
        String formCode = e.path("legalForm").path("id").asText("");
        if (!formCode.isEmpty() && !"8888".equals(formCode)) tasks.put("legalForm", () -> legalForm(formCode));
        Map<String, Object> owners = Parallel.all(tasks);
        Object form = owners.remove("legalForm");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("lei", lei);
        out.put("legalName", e.path("legalName").path("name").asText());
        List<String> other = new ArrayList<>();
        e.path("otherNames").forEach(n -> other.add(n.path("name").asText()));
        if (!other.isEmpty()) out.put("otherNames", String.join(", ", other));
        out.put("registryNumber", e.path("registeredAs").asText(null));
        out.put("jurisdiction", e.path("jurisdiction").asText());
        // GLEIF stores an ISO 20275 code ("6QQB"); show its name ("Aktiengesellschaft") when available
        out.put("legalForm", form instanceof String name ? name
                : e.path("legalForm").path("other").asText(formCode));
        out.put("status", e.path("status").asText() + " (LEI " + a.path("registration").path("status").asText() + ")");
        out.put("founded", e.path("creationDate").asText("").replaceFirst("T.*", ""));
        out.put("legalAddress", address(e.path("legalAddress")));
        String hq = address(e.path("headquartersAddress"));
        if (!hq.equals(out.get("legalAddress"))) out.put("headquarters", hq);
        out.putAll(owners);
        out.put("lastUpdated", a.path("registration").path("lastUpdateDate").asText("").replaceFirst("T.*", ""));
        out.put("url", "https://search.gleif.org/#/record/" + lei);
        return out;
    }

    private Object parent(String lei, String relation) throws Exception {
        JsonNode r = http.getJsonOrNull(GLEIF + "lei-records/" + lei + "/" + relation, TIMEOUT);
        if (r == null) return "none reported";
        JsonNode d = r.path("data");
        return d.path("attributes").path("entity").path("legalName").path("name").asText()
                + " (" + d.path("attributes").path("entity").path("legalAddress").path("country").asText()
                + ", LEI " + d.path("id").asText() + ")";
    }

    private Object legalForm(String code) throws Exception {
        JsonNode r = http.getJsonOrNull(GLEIF + "entity-legal-forms/" + code, TIMEOUT);
        JsonNode names = r == null ? null : r.path("data").path("attributes").path("names");
        if (names == null || names.isEmpty()) return null;
        String local = names.get(0).path("localName").asText();
        for (JsonNode n : names) {
            if ("English".equals(n.path("language").asText()) && !n.path("localName").asText().equals(local)) {
                return local + " (" + n.path("localName").asText() + ")";
            }
        }
        return local;
    }

    private Object children(String lei) throws Exception {
        JsonNode r = http.getJsonOrNull(GLEIF + "lei-records/" + lei + "/direct-children?page%5Bsize%5D=10", TIMEOUT);
        if (r == null || r.path("data").isEmpty()) return "none reported";
        List<String> names = new ArrayList<>();
        r.path("data").forEach(d -> names.add(d.path("attributes").path("entity").path("legalName").path("name").asText()
                + " (" + d.path("attributes").path("entity").path("legalAddress").path("country").asText() + ")"));
        int total = r.path("meta").path("pagination").path("total").asInt();
        return total + " direct" + (total > names.size() ? ", first " + names.size() : "") + ": " + String.join("; ", names);
    }

    private static String address(JsonNode a) {
        List<String> parts = new ArrayList<>();
        a.path("addressLines").forEach(l -> parts.add(l.asText()));
        parts.add((a.path("postalCode").asText("") + " " + a.path("city").asText("")).trim());
        parts.add(a.path("country").asText(""));
        parts.removeIf(String::isBlank);
        return String.join(", ", parts);
    }

    // --------------------------------------------------------------- SEC EDGAR

    private Object edgar(String name) throws Exception {
        JsonNode s = http.getJsonOrNull("https://efts.sec.gov/LATEST/search-index?keysTyped=" + enc(name), TIMEOUT,
                secHeaders, Set.of(404));
        JsonNode hits = s == null ? null : s.path("hits").path("hits");
        if (hits == null || hits.isEmpty()) return "no SEC filer with that name";

        List<String> matches = new ArrayList<>();
        hits.forEach(h -> {
            if (matches.size() < 5) matches.add(h.path("_source").path("entity").asText() + " (CIK " + h.path("_id").asText() + ")");
        });
        String cik = String.format("%010d", Long.parseLong(hits.get(0).path("_id").asText("0")));
        JsonNode d = http.getJsonOrNull("https://data.sec.gov/submissions/CIK" + cik + ".json", TIMEOUT,
                secHeaders, Set.of(404));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("topMatches", matches);
        if (d == null) return out;
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("name", d.path("name").asText());
        first.put("cik", cik);
        List<String> tickers = new ArrayList<>();
        d.path("tickers").forEach(t -> tickers.add(t.asText()));
        if (!tickers.isEmpty()) first.put("tickers", String.join(", ", tickers));
        first.put("industry", d.path("sicDescription").asText(null));
        first.put("incorporatedIn", d.path("stateOfIncorporationDescription").asText(null));
        first.put("filerCategory", d.path("category").asText(null));
        JsonNode biz = d.path("addresses").path("business");
        first.put("businessAddress", String.join(", ", List.of(biz.path("street1").asText(""), biz.path("city").asText(""),
                biz.path("stateOrCountryDescription").asText(""), biz.path("zipCode").asText("")).stream()
                .filter(x -> !x.isBlank() && !"null".equals(x)).toList()));
        List<String> former = new ArrayList<>();
        d.path("formerNames").forEach(f -> former.add(f.path("name").asText() + " (until "
                + f.path("to").asText("").replaceFirst("T.*", "") + ")"));
        if (!former.isEmpty()) first.put("formerNames", String.join("; ", former));

        JsonNode recent = d.path("filings").path("recent");
        List<Map<String, Object>> filings = new ArrayList<>();
        for (int i = 0; i < Math.min(8, recent.path("form").size()); i++) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("form", recent.path("form").get(i).asText());
            f.put("date", recent.path("filingDate").get(i).asText());
            f.put("description", recent.path("primaryDocDescription").get(i).asText(""));
            f.put("url", "https://www.sec.gov/Archives/edgar/data/" + Long.parseLong(cik) + "/"
                    + recent.path("accessionNumber").get(i).asText().replace("-", "") + "/"
                    + recent.path("primaryDocument").get(i).asText());
            filings.add(f);
        }
        first.values().removeIf(v -> v == null || (v instanceof String str && (str.isBlank() || "null".equals(str))));
        first.put("latestFilings", filings);
        first.put("url", "https://www.sec.gov/cgi-bin/browse-edgar?action=getcompany&CIK=" + cik);
        out.put("firstMatch", first);
        return out;
    }

    // -------------------------------------------------------------------- VIES

    private Map<String, Object> vies(String country, String number) throws Exception {
        JsonNode v = http.getJsonOrNull("https://ec.europa.eu/taxation_customs/vies/rest-api/ms/" + country + "/vat/"
                + number, TIMEOUT);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("vatNumber", country + number);
        if (v == null) {
            out.put("valid", "unknown, VIES didn't answer");
            return out;
        }
        boolean valid = v.path("isValid").asBoolean();
        out.put("valid", valid);
        if (!valid) {
            String err = v.path("userError").asText("");
            if (!err.isEmpty() && !"INVALID".equals(err) && !"VALID".equals(err)) {
                out.put("note", "VIES: " + err + " (the country's VAT system may be temporarily unavailable)");
            }
            return out;
        }
        String name = v.path("name").asText("---");
        String addr = v.path("address").asText("---");
        out.put("name", "---".equals(name) ? null : name.strip());
        out.put("address", "---".equals(addr) ? null : addr.strip().replace("\n", ", "));
        if ("---".equals(name)) out.put("note", "This country doesn't publish the registered name via VIES (e.g. Germany)");
        return out;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
