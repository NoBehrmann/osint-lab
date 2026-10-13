package com.osintlab.tools.accounts;

import com.fasterxml.jackson.databind.JsonNode;
import com.osintlab.support.HttpSupport;
import com.osintlab.support.Inputs;
import com.osintlab.support.Parallel;
import com.osintlab.tools.OsintTool;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Real profile DATA for a username (join dates, bios, linked accounts, activity) from sites with
 * free public APIs: Bluesky (+ handle history), Keybase (cryptographically verified linked accounts),
 * Reddit (via the Arctic Shift archive), Hacker News, Mastodon (mastodon.social), Lichess, Chess.com,
 * Steam and Telegram. Sherlock/Maigret say WHERE a username exists; this says WHO is behind it.
 * Same username on two sites doesn't prove the same person, so compare the details.
 */
@Component
public class ProfilesTool implements OsintTool {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final Pattern TELEGRAM_NAME = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{4,31}$");
    private static final String ARCTIC = "https://arctic-shift.photon-reddit.com/api/";

    private final HttpSupport http;

    public ProfilesTool(HttpSupport http) {
        this.http = http;
    }

    @Override
    public String id() {
        return "profiles";
    }

    @Override
    public Duration cacheTtl() {
        return Duration.ofHours(24);
    }

    @Override
    public Object run(String target) throws Exception {
        String u = Inputs.username(target.trim().replaceFirst("^@", ""));

        Map<String, Callable<Object>> tasks = new LinkedHashMap<>();
        tasks.put("Bluesky", () -> bluesky(u));
        tasks.put("Keybase", () -> keybase(u));
        tasks.put("Reddit", () -> reddit(u));
        tasks.put("Hacker News", () -> hackerNews(u));
        tasks.put("Mastodon (mastodon.social)", () -> mastodon(u));
        tasks.put("Lichess", () -> lichess(u));
        tasks.put("Chess.com", () -> chessCom(u));
        tasks.put("Steam", () -> steam(u));
        tasks.put("Telegram", () -> telegram(u));
        Map<String, Object> results = Parallel.all(tasks);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("username", u);
        List<String> found = new ArrayList<>();
        List<String> notFound = new ArrayList<>();
        Map<String, Object> profiles = new LinkedHashMap<>();
        Map<String, Object> errors = new LinkedHashMap<>();
        results.forEach((site, r) -> {
            if (r == null) notFound.add(site);
            else if (Parallel.failed(r)) errors.put(site, ((Map<?, ?>) r).get("error"));
            else {
                found.add(site);
                if (r instanceof Map<?, ?> m) m.values().removeIf(v -> v == null || (v instanceof String s && s.isBlank()));
                profiles.put(site, r);
            }
        });
        out.put("found", found.isEmpty() ? "none" : String.join(", ", found));
        out.putAll(profiles);
        if (!notFound.isEmpty()) out.put("notFound", String.join(", ", notFound));
        if (!errors.isEmpty()) out.put("unavailable", errors);
        out.put("note", "The same username on two sites doesn't prove it's the same person. Compare names, "
                + "dates, bios and linked accounts.");
        return out;
    }

    private Object bluesky(String u) throws Exception {
        String handle = u.contains(".") ? u : u + ".bsky.social";
        JsonNode p = http.getJsonOrNull("https://public.api.bsky.app/xrpc/app.bsky.actor.getProfile?actor=" + handle,
                TIMEOUT, Map.of(), Set.of(400, 404));
        if (p == null) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("handle", p.path("handle").asText());
        out.put("name", text(p, "displayName"));
        out.put("bio", text(p, "description"));
        out.put("created", p.path("createdAt").asText());
        out.put("followers", p.path("followersCount").asLong());
        out.put("following", p.path("followsCount").asLong());
        out.put("posts", p.path("postsCount").asLong());
        String did = p.path("did").asText();
        out.put("did", did);
        if (did.startsWith("did:plc:")) {
            JsonNode log = http.getJsonOrNull("https://plc.directory/" + did + "/log/audit", TIMEOUT);
            if (log != null) {
                // every handle the account has used, oldest first. Renamed accounts keep their history here
                Set<String> handles = new LinkedHashSet<>();
                String pds = null;
                for (JsonNode op : log) {
                    op.path("operation").path("alsoKnownAs").forEach(a -> handles.add(a.asText().replace("at://", "")));
                    String endpoint = op.path("operation").path("services").path("atproto_pds").path("endpoint").asText("");
                    if (!endpoint.isEmpty()) pds = endpoint;
                }
                out.put("handleHistory", String.join(" -> ", handles));
                out.put("hostedOn", pds);
            }
        }
        out.put("url", "https://bsky.app/profile/" + p.path("handle").asText());
        return out;
    }

    private Object keybase(String u) throws Exception {
        JsonNode d = http.getJsonOrNull("https://keybase.io/_/api/1.0/user/lookup.json?usernames=" + u
                + "&fields=basics,profile,proofs_summary", TIMEOUT);
        JsonNode them = d == null ? null : d.path("them").path(0);
        if (them == null || them.isMissingNode() || them.isNull()) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", text(them.path("profile"), "full_name"));
        out.put("location", text(them.path("profile"), "location"));
        out.put("bio", text(them.path("profile"), "bio"));
        out.put("created", epoch(them.path("basics").path("ctime").asLong()));
        List<Map<String, Object>> proofs = new ArrayList<>();
        for (JsonNode pr : them.path("proofs_summary").path("all")) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("service", pr.path("proof_type").asText());
            row.put("account", pr.path("nametag").asText());
            row.put("url", pr.path("service_url").asText());
            proofs.add(row);
        }
        out.put("verifiedAccounts", proofs);
        out.put("url", "https://keybase.io/" + u);
        return out;
    }

    /** Reddit blocks anonymous API use, so this reads the Arctic Shift archive of public Reddit data. */
    private Object reddit(String u) throws Exception {
        Map<String, Callable<Object>> q = new LinkedHashMap<>();
        q.put("firstPost", () -> first(ARCTIC + "posts/search?author=" + u + "&limit=1&sort=asc"));
        q.put("latestPost", () -> first(ARCTIC + "posts/search?author=" + u + "&limit=1&sort=desc"));
        q.put("firstComment", () -> first(ARCTIC + "comments/search?author=" + u + "&limit=1&sort=asc"));
        q.put("latestComment", () -> first(ARCTIC + "comments/search?author=" + u + "&limit=1&sort=desc"));
        q.put("topSubreddits", () -> http.getJsonOrNull(ARCTIC + "users/interactions/subreddits?author=" + u
                + "&limit=8", Duration.ofSeconds(25)));
        Map<String, Object> r = Parallel.all(q);

        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : List.of("firstPost", "latestPost", "firstComment", "latestComment")) {
            if (r.get(key) instanceof JsonNode item) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("date", epoch(item.path("created_utc").asLong()));
                row.put("subreddit", "r/" + item.path("subreddit").asText());
                if (item.hasNonNull("title")) row.put("title", item.path("title").asText());
                String link = item.path("permalink").asText("");
                if (!link.isEmpty()) row.put("url", "https://www.reddit.com" + link);
                out.put(key, row);
            }
        }
        if (r.get("topSubreddits") instanceof JsonNode subs) {
            List<String> rows = new ArrayList<>();
            subs.path("data").forEach(s -> rows.add("r/" + s.path("subreddit").asText() + " (" + s.path("count").asInt() + ")"));
            if (!rows.isEmpty()) out.put("topSubreddits", String.join(", ", rows));
        }
        if (out.isEmpty()) {
            // all lookups failed rather than "no activity", so report it instead of "not found"
            if (r.values().stream().allMatch(Parallel::failed)) return Map.of("error", "Arctic Shift archive unavailable");
            return null;
        }
        out.put("url", "https://www.reddit.com/user/" + u);
        out.put("source", "Arctic Shift archive. Includes since-deleted content, may lag a few days");
        return out;
    }

    private JsonNode first(String url) throws Exception {
        JsonNode d = http.getJsonOrNull(url, Duration.ofSeconds(25));
        JsonNode item = d == null ? null : d.path("data").path(0);
        return item == null || item.isMissingNode() ? null : item;
    }

    private Object hackerNews(String u) throws Exception {
        if (u.contains(".")) return null; // not a valid HN name, and a '.' makes the Firebase API return 400
        JsonNode p = http.getJsonOrNull("https://hacker-news.firebaseio.com/v0/user/" + u + ".json", TIMEOUT);
        if (p == null || p.isNull()) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("created", epoch(p.path("created").asLong()));
        out.put("karma", p.path("karma").asLong());
        out.put("about", stripHtml(p.path("about").asText("")));
        out.put("submissions", p.path("submitted").size());
        out.put("url", "https://news.ycombinator.com/user?id=" + u);
        return out;
    }

    private Object mastodon(String u) throws Exception {
        JsonNode p = http.getJsonOrNull("https://mastodon.social/api/v1/accounts/lookup?acct=" + u, TIMEOUT);
        if (p == null) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", text(p, "display_name"));
        out.put("bio", stripHtml(p.path("note").asText("")));
        out.put("created", p.path("created_at").asText());
        out.put("lastPost", text(p, "last_status_at"));
        out.put("followers", p.path("followers_count").asLong());
        out.put("posts", p.path("statuses_count").asLong());
        out.put("bot", p.path("bot").asBoolean());
        List<String> links = new ArrayList<>();
        for (JsonNode f : p.path("fields")) {
            links.add(f.path("name").asText() + ": " + stripHtml(f.path("value").asText())
                    + (f.path("verified_at").isNull() ? "" : " (verified)"));
        }
        if (!links.isEmpty()) out.put("profileLinks", links);
        out.put("url", p.path("url").asText());
        return out;
    }

    private Object lichess(String u) throws Exception {
        JsonNode p = http.getJsonOrNull("https://lichess.org/api/user/" + u, TIMEOUT);
        if (p == null) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        JsonNode pr = p.path("profile");
        out.put("realName", text(pr, "realName"));
        out.put("location", text(pr, "location"));
        out.put("country", text(pr, "flag"));
        out.put("bio", text(pr, "bio"));
        out.put("links", text(pr, "links"));
        out.put("title", text(p, "title"));
        out.put("created", epoch(p.path("createdAt").asLong() / 1000));
        out.put("lastSeen", epoch(p.path("seenAt").asLong() / 1000));
        out.put("games", p.path("count").path("all").asLong());
        if (p.path("disabled").asBoolean()) out.put("status", "account closed");
        if (p.path("tosViolation").asBoolean()) out.put("status", "marked for terms-of-service violation");
        List<String> streams = new ArrayList<>();
        p.path("streamer").properties().forEach(e -> streams.add(e.getValue().path("channel").asText()));
        if (!streams.isEmpty()) out.put("streams", String.join(", ", streams));
        out.put("url", "https://lichess.org/@/" + p.path("username").asText(u));
        return out;
    }

    private Object chessCom(String u) throws Exception {
        JsonNode p = http.getJsonOrNull("https://api.chess.com/pub/player/" + u.toLowerCase(), TIMEOUT);
        if (p == null) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", text(p, "name"));
        out.put("location", text(p, "location"));
        String country = p.path("country").asText("");
        out.put("country", country.isEmpty() ? null : country.substring(country.lastIndexOf('/') + 1));
        out.put("title", text(p, "title"));
        out.put("joined", epoch(p.path("joined").asLong()));
        out.put("lastOnline", epoch(p.path("last_online").asLong()));
        out.put("followers", p.path("followers").asLong());
        out.put("status", text(p, "status"));
        out.put("url", p.path("url").asText());
        return out;
    }

    /** Steam "custom URL" profiles (steamcommunity.com/id/NAME). Parsed with regexes, no XML parser. */
    private Object steam(String u) throws Exception {
        HttpSupport.Fetched f = http.get(URI.create("https://steamcommunity.com/id/" + u + "?xml=1"), TIMEOUT,
                "text/xml", 500_000, true);
        String xml = f.body();
        if (f.status() != 200 || xml.contains("<error>") || !xml.contains("<steamID64>")) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("displayName", xmlTag(xml, "steamID"));
        out.put("realName", xmlTag(xml, "realname"));
        out.put("location", xmlTag(xml, "location"));
        out.put("memberSince", xmlTag(xml, "memberSince"));
        out.put("privacy", xmlTag(xml, "privacyState"));
        out.put("vacBanned", "1".equals(xmlTag(xml, "vacBanned")));
        out.put("steamId64", xmlTag(xml, "steamID64"));
        out.put("url", "https://steamcommunity.com/id/" + u);
        return out;
    }

    /** Public t.me preview page: display name, bio and subscriber/member count. */
    private Object telegram(String u) throws Exception {
        if (!TELEGRAM_NAME.matcher(u).matches()) return null; // not a valid Telegram username
        HttpSupport.Fetched f = http.get(URI.create("https://t.me/" + u), TIMEOUT, "text/html", 500_000, true);
        String html = f.body();
        String title = meta(html, "og:title");
        String extra = match(html, "tgme_page_extra\">([^<]*)<");
        if (f.status() != 200 || title == null || (title.startsWith("Telegram: Contact @") && extra == null)) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", title);
        out.put("bio", meta(html, "og:description"));
        out.put("info", extra == null ? null : unescape(extra.trim()));
        out.put("url", "https://t.me/" + u);
        return out;
    }

    // ----------------------------------------------------------------- helpers

    private static String text(JsonNode node, String field) {
        String s = node.path(field).asText("");
        return s.isBlank() || "null".equals(s) ? null : s.strip();
    }

    private static String epoch(long seconds) {
        return seconds <= 0 ? null : Instant.ofEpochSecond(seconds).toString().substring(0, 10);
    }

    private static String stripHtml(String html) {
        String s = html.replaceAll("(?i)<br\\s*/?>|</p>\\s*<p>|<p>", "\n").replaceAll("<[^>]+>", "");
        s = unescape(s).strip();
        return s.isEmpty() ? null : s;
    }

    private static String unescape(String s) {
        return s.replace("&#x27;", "'").replace("&#39;", "'").replace("&quot;", "\"").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&#x2F;", "/").replace("&nbsp;", " ").replace("&amp;", "&");
    }

    private static String xmlTag(String xml, String tag) {
        String v = match(xml, "<" + tag + ">(?:<!\\[CDATA\\[)?(.*?)(?:]]>)?</" + tag + ">");
        return v == null || v.isBlank() ? null : unescape(v.strip());
    }

    private static String meta(String html, String property) {
        String v = match(html, "<meta property=\"" + Pattern.quote(property) + "\" content=\"([^\"]*)\"");
        return v == null || v.isBlank() ? null : unescape(v);
    }

    private static String match(String text, String regex) {
        Matcher m = Pattern.compile(regex, Pattern.DOTALL).matcher(text);
        return m.find() ? m.group(1) : null;
    }
}
