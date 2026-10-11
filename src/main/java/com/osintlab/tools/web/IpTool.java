package com.osintlab.tools.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.osintlab.support.HttpSupport;
import com.osintlab.support.Inputs;
import com.osintlab.tools.OsintTool;
import com.osintlab.tools.dns.DnsService;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Geolocation / network owner via ip-api.com (free tier: HTTP only, 45 requests/minute),
 * plus reputation link-outs.
 */
@Component
public class IpTool implements OsintTool {

    private static final String FIELDS =
            "status,message,continent,country,countryCode,regionName,city,zip,lat,lon,timezone,isp,org,as,asname,mobile,proxy,hosting";

    private final HttpSupport http;
    private final DnsService dns;

    public IpTool(HttpSupport http, DnsService dns) {
        this.http = http;
        this.dns = dns;
    }

    @Override
    public String id() {
        return "ip";
    }

    @Override
    public int maxConcurrent() {
        return 2;
    }

    @Override
    public Duration cacheTtl() {
        return Duration.ofHours(24);
    }

    @Override
    public Object run(String target) throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        String ip;
        if (Inputs.isIp(target)) {
            ip = target.trim();
        } else {
            String domain = Inputs.domain(target);
            ip = InetAddress.getByName(domain).getHostAddress();
            out.put("resolvedFrom", domain);
        }
        out.put("ip", ip);
        out.put("reverseDns", dns.reverse(ip));

        JsonNode geo = http.getJson("http://ip-api.com/json/" + ip + "?fields=" + FIELDS, Duration.ofSeconds(10));
        if (!"success".equals(geo.path("status").asText())) {
            out.put("geo", "unavailable: " + geo.path("message").asText("unknown error"));
        } else {
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("location", String.join(", ", geo.path("city").asText(), geo.path("regionName").asText(),
                    geo.path("country").asText()));
            g.put("coordinates", geo.path("lat").asText() + ", " + geo.path("lon").asText());
            g.put("timezone", geo.path("timezone").asText());
            g.put("isp", geo.path("isp").asText());
            g.put("org", geo.path("org").asText());
            g.put("as", geo.path("as").asText());
            g.put("asName", geo.path("asname").asText());
            g.put("mobile", geo.path("mobile").asBoolean());
            g.put("proxyOrVpn", geo.path("proxy").asBoolean());
            g.put("hostingOrDatacenter", geo.path("hosting").asBoolean());
            g.put("map", "https://www.openstreetmap.org/?mlat=" + geo.path("lat").asText() + "&mlon="
                    + geo.path("lon").asText() + "#map=11/" + geo.path("lat").asText() + "/" + geo.path("lon").asText());
            g.put("note", "IP geolocation is approximate (city level at best)");
            out.put("geo", g);
        }

        Map<String, String> links = new LinkedHashMap<>();
        links.put("Shodan", "https://www.shodan.io/host/" + ip);
        links.put("Censys", "https://search.censys.io/hosts/" + ip);
        links.put("VirusTotal", "https://www.virustotal.com/gui/ip-address/" + ip);
        links.put("AbuseIPDB", "https://www.abuseipdb.com/check/" + ip);
        links.put("GreyNoise", "https://viz.greynoise.io/ip/" + ip);
        links.put("AlienVault OTX", "https://otx.alienvault.com/indicator/ip/" + ip);
        out.put("reputation", links);
        return out;
    }
}
