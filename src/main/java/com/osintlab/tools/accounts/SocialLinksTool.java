package com.osintlab.tools.accounts;

import com.osintlab.support.Inputs;
import com.osintlab.support.PhoneNumbers;
import com.osintlab.tools.OsintTool;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Direct links into the big platforms (Facebook, Instagram, X, TikTok, LinkedIn...). These sites
 * answer automated checks with login walls or identical pages for existing and non-existing
 * accounts, so no tool can verify them reliably. The links let you check by hand in one click.
 * Makes no requests itself.
 */
@Component
public class SocialLinksTool implements OsintTool {

    private final PhoneNumbers numbers;

    public SocialLinksTool(PhoneNumbers numbers) {
        this.numbers = numbers;
    }

    @Override
    public String id() {
        return "social";
    }

    @Override
    public Object run(String target) throws Exception {
        String t = Inputs.require(target, "target");
        if (t.contains("@") && !t.startsWith("@")) return forEmail(Inputs.email(t));
        if (t.matches("^\\+?[0-9 ().\\-/]+$")) return forPhone(Inputs.phone(t));
        return forUsername(Inputs.username(t));
    }

    private static Map<String, Object> forUsername(String u) {
        List<Map<String, String>> links = new ArrayList<>();
        add(links, "Facebook", "https://www.facebook.com/" + u);
        add(links, "Instagram", "https://www.instagram.com/" + u + "/");
        add(links, "X / Twitter", "https://x.com/" + u);
        add(links, "TikTok", "https://www.tiktok.com/@" + u);
        add(links, "YouTube", "https://www.youtube.com/@" + u);
        add(links, "Threads", "https://www.threads.net/@" + u);
        add(links, "LinkedIn", "https://www.linkedin.com/in/" + u);
        add(links, "Snapchat", "https://www.snapchat.com/add/" + u);
        add(links, "Pinterest", "https://www.pinterest.com/" + u + "/");
        add(links, "Reddit", "https://www.reddit.com/user/" + u);
        add(links, "Telegram", "https://t.me/" + u);
        add(links, "Twitch", "https://www.twitch.tv/" + u);
        add(links, "Bluesky", "https://bsky.app/profile/" + u + ".bsky.social");
        add(links, "Mastodon", "https://mastodon.social/@" + u);
        add(links, "GitHub", "https://github.com/" + u);
        add(links, "Google (exact)", search("\"" + u + "\""));
        return result(u, "username", links);
    }

    private static Map<String, Object> forEmail(String email) {
        List<Map<String, String>> links = new ArrayList<>();
        String q = enc(email);
        add(links, "Facebook search (login)", "https://www.facebook.com/search/top?q=" + q);
        add(links, "X / Twitter search", "https://x.com/search?q=" + enc("\"" + email + "\"") + "&f=live");
        add(links, "LinkedIn (Google)", search("site:linkedin.com \"" + email + "\""));
        add(links, "Instagram (Google)", search("site:instagram.com \"" + email + "\""));
        add(links, "GitHub commits", "https://github.com/search?type=commits&q=" + enc("author-email:" + email));
        add(links, "Google (exact)", search("\"" + email + "\""));
        add(links, "Bing (exact)", "https://www.bing.com/search?q=" + enc("\"" + email + "\""));
        add(links, "Have I Been Pwned", "https://haveibeenpwned.com/");
        add(links, "Epieos", "https://epieos.com/?q=" + q + "&t=email");
        return result(email, "email", links);
    }

    /**
     * WhatsApp and Telegram links only work with the full international number, so a national number
     * ("0151...") is converted first; Google searches both spellings.
     */
    private Map<String, Object> forPhone(String phone) throws Exception {
        String intl = numbers.e164(phone).orElse(phone);
        List<Map<String, String>> links = new ArrayList<>();
        String digits = intl.replace("+", "");
        add(links, "WhatsApp", "https://wa.me/" + digits);
        add(links, "Telegram", "https://t.me/+" + digits);
        add(links, "Facebook search (login)", "https://www.facebook.com/search/top?q=" + enc(intl));
        String google = intl.equals(phone) ? "\"" + intl + "\"" : "\"" + intl + "\" OR \"" + phone + "\"";
        add(links, "Google (exact)", search(google));
        add(links, "Sync.me", "https://sync.me/search/?number=" + enc(intl));
        return result(intl, "phone", links);
    }

    private static Map<String, Object> result(String value, String kind, List<Map<String, String>> links) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(kind, value);
        out.put("links", links);
        out.put("note", "Not checked automatically: these platforms block automated lookups. Open each link "
                + "(logged in where needed) to verify.");
        return out;
    }

    private static void add(List<Map<String, String>> links, String platform, String url) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("platform", platform);
        row.put("url", url);
        links.add(row);
    }

    private static String search(String q) {
        return "https://www.google.com/search?q=" + enc(q);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
