package com.osintlab.tools.accounts;

import com.osintlab.support.HttpSupport;
import com.osintlab.support.Inputs;
import com.osintlab.tools.OsintTool;
import com.osintlab.tools.dns.DnsService;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Email address analysis: mail setup of the domain, provider type and Gravatar. */
@Component
public class EmailTool implements OsintTool {

    private static final Set<String> FREE_PROVIDERS = Set.of(
            "gmail.com", "googlemail.com", "yahoo.com", "yahoo.de", "outlook.com", "hotmail.com", "hotmail.de",
            "live.com", "msn.com", "icloud.com", "me.com", "aol.com", "gmx.de", "gmx.net", "gmx.at", "gmx.ch",
            "web.de", "t-online.de", "freenet.de", "posteo.de", "mail.de", "proton.me", "protonmail.com",
            "tutanota.com", "tuta.io", "yandex.ru", "yandex.com", "mail.ru", "zoho.com", "mail.com");
    private static final Set<String> DISPOSABLE = Set.of(
            "mailinator.com", "guerrillamail.com", "10minutemail.com", "temp-mail.org", "yopmail.com",
            "trashmail.com", "sharklasers.com", "getnada.com", "dispostable.com", "maildrop.cc",
            "throwawaymail.com", "tempmail.com", "fakeinbox.com", "mailnesia.com", "emailondeck.com");
    private static final Map<String, String> MX_PROVIDERS = Map.ofEntries(
            Map.entry("google.com", "Google Workspace / Gmail"),
            Map.entry("googlemail.com", "Google Workspace / Gmail"),
            Map.entry("outlook.com", "Microsoft 365 / Outlook"),
            Map.entry("pphosted.com", "Proofpoint"),
            Map.entry("mimecast.com", "Mimecast"),
            Map.entry("protonmail.ch", "Proton Mail"),
            Map.entry("zoho.com", "Zoho Mail"),
            Map.entry("zoho.eu", "Zoho Mail"),
            Map.entry("yandex.net", "Yandex"),
            Map.entry("ovh.net", "OVH"),
            Map.entry("ionos.de", "IONOS"),
            Map.entry("kundenserver.de", "IONOS"),
            Map.entry("gmx.net", "GMX"),
            Map.entry("web.de", "WEB.DE"),
            Map.entry("icloud.com", "iCloud"),
            Map.entry("yahoodns.net", "Yahoo"),
            Map.entry("messagelabs.com", "Broadcom / Symantec"),
            Map.entry("barracudanetworks.com", "Barracuda"));

    private final DnsService dns;
    private final HttpSupport http;

    public EmailTool(DnsService dns, HttpSupport http) {
        this.dns = dns;
        this.http = http;
    }

    @Override
    public String id() {
        return "email";
    }

    @Override
    public Object run(String target) throws Exception {
        String email = Inputs.email(target);
        String local = email.substring(0, email.indexOf('@'));
        String domain = email.substring(email.indexOf('@') + 1);

        List<String> mx = dns.query(domain, "MX");
        List<String> txt = dns.query(domain, "TXT");
        List<String> dmarc = dns.query("_dmarc." + domain, "TXT");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("email", email);
        out.put("domain", domain);
        out.put("canReceiveMail", !mx.isEmpty() || !dns.query(domain, "A").isEmpty());
        out.put("mailProvider", provider(mx));
        out.put("freeProvider", FREE_PROVIDERS.contains(domain));
        out.put("disposable", DISPOSABLE.contains(domain));
        out.put("mx", mx);
        out.put("spf", txt.stream().filter(t -> t.toLowerCase().startsWith("v=spf1")).findFirst().orElse(null));
        out.put("dmarc", dmarc.stream().filter(t -> t.toLowerCase().startsWith("v=dmarc1")).findFirst().orElse(null));
        out.put("possibleUsername", local.replaceAll("\\+.*$", ""));
        out.put("gravatar", gravatar(email));
        return out;
    }

    private static String provider(List<String> mx) {
        for (String record : mx) {
            String host = record.substring(record.indexOf(' ') + 1).toLowerCase();
            for (var e : MX_PROVIDERS.entrySet()) {
                if (host.endsWith(e.getKey())) return e.getValue();
            }
        }
        return mx.isEmpty() ? null : "self-hosted / other";
    }

    /** Gravatar profile picture (and public profile, if any) for the address. */
    private Map<String, Object> gravatar(String email) {
        Map<String, Object> g = new LinkedHashMap<>();
        try {
            String hash = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(email.getBytes(StandardCharsets.UTF_8)));
            HttpSupport.Fetched avatar = http.get(URI.create("https://gravatar.com/avatar/" + hash + "?d=404&s=200"),
                    Duration.ofSeconds(8), null, 1, true);
            g.put("hasAvatar", avatar.status() == 200);
            if (avatar.status() == 200) {
                g.put("avatar", "https://gravatar.com/avatar/" + hash + "?s=400");
                g.put("profile", "https://gravatar.com/" + hash);
            }
        } catch (Exception e) {
            g.put("hasAvatar", "check failed: " + e.getMessage());
        }
        return g;
    }
}
