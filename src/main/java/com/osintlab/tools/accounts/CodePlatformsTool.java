package com.osintlab.tools.accounts;

import com.fasterxml.jackson.databind.JsonNode;
import com.osintlab.support.HttpSupport;
import com.osintlab.support.Inputs;
import com.osintlab.support.Parallel;
import com.osintlab.support.UpstreamException;
import com.osintlab.tools.OsintTool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * Code platforms are one of the most reliable identity sources: commits carry the author's real name
 * and email address, and key uploads link accounts together.
 * <ul>
 *   <li>Username: GitHub profile, organisations, SSH/GPG keys, recent repos and the name/email pairs
 *       used in the user's own commits; GitLab.com and Codeberg profiles (+ GitLab commit emails).</li>
 *   <li>Email: which GitHub accounts committed with that address. GitHub only links a commit to an
 *       account when the email is verified on it, so a linked login is a strong signal.</li>
 * </ul>
 * GitHub allows 60 requests/hour without a token; {@code osintlab.github.token} (secrets.properties,
 * a free fine-grained token with no permissions) raises that to 5000.
 */
@Component
public class CodePlatformsTool implements OsintTool {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final String GH = "https://api.github.com";

    private final HttpSupport http;
    private final Map<String, String> githubHeaders;

    public CodePlatformsTool(HttpSupport http, @Value("${osintlab.github.token:}") String token) {
        this.http = http;
        Map<String, String> h = new LinkedHashMap<>();
        h.put("X-GitHub-Api-Version", "2022-11-28");
        if (!token.isBlank()) h.put("Authorization", "Bearer " + token.trim());
        this.githubHeaders = Map.copyOf(h);
    }

    @Override
    public String id() {
        return "code";
    }

    @Override
    public int maxConcurrent() {
        return 2; // GitHub's anonymous limits are tight
    }

    @Override
    public Duration cacheTtl() {
        return Duration.ofHours(24);
    }

    @Override
    public Object run(String target) throws Exception {
        String t = Inputs.require(target, "target");
        return t.contains("@") ? forEmail(Inputs.email(t)) : forUsername(Inputs.username(t));
    }

    // ---------------------------------------------------------------- username

    private Object forUsername(String u) throws Exception {
        Map<String, Callable<Object>> tasks = new LinkedHashMap<>();
        tasks.put("GitHub", () -> github(u));
        tasks.put("GitLab", () -> gitlab(u));
        tasks.put("Codeberg", () -> codeberg(u));
        Map<String, Object> results = Parallel.all(tasks);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("username", u);
        List<String> notFound = new ArrayList<>();
        results.forEach((platform, r) -> {
            if (r == null) notFound.add(platform);
            else out.put(platform, r);
        });
        if (!notFound.isEmpty()) out.put("notFound", notFound);
        out.put("note", "Commit emails are what the person configured in git - they may be old, work or no-reply "
                + "addresses. The same SSH key fingerprint on two accounts means the same person or machine.");
        return out;
    }

    private Object github(String u) throws Exception {
        JsonNode p = gh("/users/" + u);
        if (p == null) return null;

        Map<String, Callable<Object>> tasks = new LinkedHashMap<>();
        tasks.put("organisations", () -> {
            List<String> orgs = new ArrayList<>();
            JsonNode a = gh("/users/" + u + "/orgs");
            if (a != null) a.forEach(o -> orgs.add(o.path("login").asText()));
            return orgs;
        });
        tasks.put("sshKeys", () -> sshKeys(gh("/users/" + u + "/keys")));
        tasks.put("gpgKeys", () -> gpgKeys(gh("/users/" + u + "/gpg_keys")));
        tasks.put("repos", () -> gh("/users/" + u + "/repos?type=owner&sort=pushed&per_page=10"));
        Map<String, Object> extra = Parallel.all(tasks);

        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("login", p.path("login").asText());
        profile.put("name", text(p, "name"));
        profile.put("company", text(p, "company"));
        profile.put("location", text(p, "location"));
        profile.put("publicEmail", text(p, "email"));
        profile.put("blog", text(p, "blog"));
        profile.put("twitter", text(p, "twitter_username"));
        profile.put("bio", text(p, "bio"));
        profile.put("created", p.path("created_at").asText());
        profile.put("lastUpdated", p.path("updated_at").asText());
        profile.put("publicRepos", p.path("public_repos").asInt());
        profile.put("followers", p.path("followers").asInt());
        profile.put("url", p.path("html_url").asText());
        profile.values().removeIf(Objects::isNull);
        out.put("profile", profile);
        out.put("organisations", extra.get("organisations"));

        Object repos = extra.get("repos");
        if (repos instanceof JsonNode r) {
            out.put("commitIdentities", commitIdentities(u, r));
            out.put("recentRepos", repoRows(r));
        } else {
            out.put("recentRepos", repos);
        }
        out.put("sshKeys", extra.get("sshKeys"));
        out.put("gpgKeys", extra.get("gpgKeys"));
        return out;
    }

    /** Name/email pairs from the user's own commits in their three most recently pushed own repos. */
    private List<Map<String, Object>> commitIdentities(String u, JsonNode repos) throws InterruptedException {
        Map<String, Callable<Object>> tasks = new LinkedHashMap<>();
        int n = 0;
        for (JsonNode r : repos) {
            if (r.path("fork").asBoolean() || n >= 3) continue;
            String full = r.path("full_name").asText();
            tasks.put(full, () -> gh("/repos/" + full + "/commits?author=" + u + "&per_page=30"));
            n++;
        }
        Map<String, Map<String, Object>> byEmail = new LinkedHashMap<>();
        for (Object result : Parallel.all(tasks).values()) {
            if (!(result instanceof JsonNode commits)) continue; // empty repo (409) or error - skip
            for (JsonNode c : commits) {
                JsonNode a = c.path("commit").path("author");
                String email = a.path("email").asText("").toLowerCase(Locale.ROOT);
                if (email.isEmpty()) continue;
                Map<String, Object> row = byEmail.computeIfAbsent(email, e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("email", e);
                    m.put("names", new LinkedHashSet<String>());
                    m.put("commits", 0);
                    m.put("latest", "");
                    m.put("noReply", e.endsWith("users.noreply.github.com"));
                    return m;
                });
                @SuppressWarnings("unchecked") Set<String> names = (Set<String>) row.get("names");
                names.add(a.path("name").asText());
                row.put("commits", (int) row.get("commits") + 1);
                String date = a.path("date").asText("");
                if (date.compareTo((String) row.get("latest")) > 0) row.put("latest", date);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>(byEmail.values());
        out.forEach(r -> r.put("names", joined(r.get("names"))));
        return out;
    }

    private static List<Map<String, Object>> repoRows(JsonNode repos) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode r : repos) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("repo", r.path("name").asText());
            row.put("description", text(r, "description"));
            row.put("language", text(r, "language"));
            row.put("lastPush", r.path("pushed_at").asText());
            row.put("stars", r.path("stargazers_count").asInt());
            row.put("fork", r.path("fork").asBoolean());
            row.put("url", r.path("html_url").asText());
            rows.add(row);
        }
        return rows;
    }

    private static List<Map<String, Object>> sshKeys(JsonNode keys) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (keys == null) return rows;
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        for (JsonNode k : keys) {
            String[] parts = k.path("key").asText("").split(" ");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", parts[0]);
            if (parts.length > 1) {
                try {
                    byte[] blob = Base64.getDecoder().decode(parts[1]);
                    row.put("fingerprint", "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(sha256.digest(blob)));
                } catch (IllegalArgumentException ignored) {
                    // malformed key - leave the fingerprint out
                }
            }
            row.put("added", k.path("created_at").asText(""));
            row.put("lastUsed", k.path("last_used").asText(""));
            rows.add(row);
        }
        return rows;
    }

    private static List<Map<String, Object>> gpgKeys(JsonNode keys) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (keys == null) return rows;
        for (JsonNode k : keys) {
            List<String> emails = new ArrayList<>();
            k.path("emails").forEach(e -> emails.add(e.path("email").asText()
                    + (e.path("verified").asBoolean() ? "" : " (unverified)")));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("keyId", k.path("key_id").asText());
            row.put("emails", String.join(", ", emails));
            row.put("created", k.path("created_at").asText(""));
            rows.add(row);
        }
        return rows;
    }

    private Object gitlab(String u) throws Exception {
        JsonNode users = http.getJsonOrNull("https://gitlab.com/api/v4/users?username=" + u, TIMEOUT);
        if (users == null || users.isEmpty()) return null;
        JsonNode p = users.get(0);
        String name = p.path("name").asText("");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        out.put("publicEmail", text(p, "public_email"));
        out.put("state", p.path("state").asText());
        out.put("url", p.path("web_url").asText());

        JsonNode projects = http.getJsonOrNull("https://gitlab.com/api/v4/users/" + p.path("id").asText()
                + "/projects?order_by=last_activity_at&per_page=5&simple=true", TIMEOUT);
        if (projects == null || projects.isEmpty()) return out;

        List<Map<String, Object>> rows = new ArrayList<>();
        Set<String> emails = new LinkedHashSet<>();
        int scanned = 0;
        for (JsonNode pr : projects) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("project", pr.path("path_with_namespace").asText());
            row.put("description", text(pr, "description"));
            row.put("lastActivity", pr.path("last_activity_at").asText());
            row.put("url", pr.path("web_url").asText());
            rows.add(row);
            if (scanned++ < 2) {
                try {
                    JsonNode commits = http.getJsonOrNull("https://gitlab.com/api/v4/projects/" + pr.path("id").asText()
                            + "/repository/commits?per_page=40", TIMEOUT);
                    if (commits != null) {
                        for (JsonNode c : commits) {
                            // only commits by this user, not by other contributors to the project
                            if (c.path("author_name").asText().equalsIgnoreCase(name)) {
                                emails.add(c.path("author_email").asText().toLowerCase(Locale.ROOT));
                            }
                        }
                    }
                } catch (UpstreamException ignored) {
                    // empty or private repository
                }
            }
        }
        emails.remove("");
        if (!emails.isEmpty()) out.put("commitEmails", String.join(", ", emails));
        out.put("projects", rows);
        return out;
    }

    private Object codeberg(String u) throws Exception {
        JsonNode p = http.getJsonOrNull("https://codeberg.org/api/v1/users/" + u, TIMEOUT);
        if (p == null) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", text(p, "full_name"));
        String email = p.path("email").asText("");
        if (!email.contains("noreply")) out.put("email", email);
        out.put("location", text(p, "location"));
        out.put("website", text(p, "website"));
        out.put("bio", text(p, "description"));
        out.put("created", p.path("created").asText());
        out.put("followers", p.path("followers_count").asInt());
        out.put("url", p.path("html_url").asText());
        return out;
    }

    // ------------------------------------------------------------------- email

    private Object forEmail(String email) throws Exception {
        // No date sort: pranksters commit "as" famous people with far-future dates, which would fill the
        // top of a date-sorted list. Best-match order plus dropping future-dated commits avoids that.
        JsonNode r = gh("/search/commits?q=" + URLEncoder.encode("author-email:" + email, StandardCharsets.UTF_8)
                + "&per_page=50");
        String tomorrow = Instant.now().plus(Duration.ofDays(1)).toString();
        Map<String, Map<String, Object>> accounts = new LinkedHashMap<>();
        int unlinked = 0;
        int futureDated = 0;
        Set<String> unlinkedNames = new LinkedHashSet<>();
        for (JsonNode item : r == null ? List.<JsonNode>of() : r.path("items")) {
            JsonNode a = item.path("commit").path("author");
            // the search matches loosely - keep only commits that really used this address
            if (!a.path("email").asText().equalsIgnoreCase(email)) continue;
            String date = isoInstant(a.path("date").asText(""));
            if (date.compareTo(tomorrow) > 0) {
                futureDated++;
                continue;
            }
            String login = item.path("author").path("login").asText("");
            if (login.isEmpty()) {
                unlinked++;
                unlinkedNames.add(a.path("name").asText());
                continue;
            }
            Map<String, Object> row = accounts.computeIfAbsent(login, l -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("account", l);
                m.put("url", "https://github.com/" + l);
                m.put("names", new LinkedHashSet<String>());
                m.put("repos", new LinkedHashSet<String>());
                m.put("commitsInSample", 0);
                m.put("latestCommit", "");
                return m;
            });
            if (date.compareTo((String) row.get("latestCommit")) > 0) row.put("latestCommit", date);
            @SuppressWarnings("unchecked") Set<String> names = (Set<String>) row.get("names");
            @SuppressWarnings("unchecked") Set<String> repos = (Set<String>) row.get("repos");
            names.add(a.path("name").asText());
            if (repos.size() < 5) repos.add(item.path("repository").path("full_name").asText());
            row.put("commitsInSample", (int) row.get("commitsInSample") + 1);
        }
        List<Map<String, Object>> rows = new ArrayList<>(accounts.values());
        rows.forEach(row -> {
            row.put("namesInCommits", joined(row.remove("names")));
            row.put("sampleRepos", joined(row.remove("repos")));
        });

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("email", email);
        out.put("githubAccounts", rows);
        if (unlinked > 0) {
            out.put("commitsNotLinkedToAnAccount", unlinked + " (names used: " + String.join(", ", unlinkedNames) + ")");
        }
        if (futureDated > 0) out.put("ignoredFakeCommits", futureDated + " future-dated commits (pranks/spoofs) left out");
        out.put("note", rows.isEmpty() && unlinked == 0
                ? "No public GitHub commits use this address."
                : "GitHub links a commit to an account only if the email is verified on that account, so the ACCOUNT "
                + "is a strong match. But anyone can put any email in their own commits, so the names and repos "
                + "seen in individual commits may be someone else's. Based on up to 50 matching commits.");
        out.put("gitlabCodeberg", "can't be searched by email without an account - try the username instead");
        return out;
    }

    // ----------------------------------------------------------------- helpers

    private JsonNode gh(String path) throws Exception {
        try {
            return http.getJsonOrNull(GH + path, TIMEOUT, githubHeaders, Set.of(404));
        } catch (UpstreamException e) {
            if (e.getMessage().contains("rate limit") && !githubHeaders.containsKey("Authorization")) {
                throw new UpstreamException("GitHub rate limit reached (60 requests/hour without a token) - wait, or add "
                        + "a free token as osintlab.github.token in secrets.properties");
            }
            throw e;
        }
    }

    /** Commit dates carry an offset ("+03:00"); normalised to UTC so they compare as strings. */
    private static String isoInstant(String date) {
        try {
            return OffsetDateTime.parse(date).toInstant().toString();
        } catch (DateTimeParseException e) {
            return "";
        }
    }

    private static String joined(Object values) {
        return ((Set<?>) values).stream().map(String::valueOf).collect(Collectors.joining(", "));
    }

    private static String text(JsonNode node, String field) {
        String s = node.path(field).asText("");
        return s.isBlank() || "null".equals(s) ? null : s.strip();
    }
}
