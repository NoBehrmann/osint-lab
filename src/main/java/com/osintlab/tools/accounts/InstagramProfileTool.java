package com.osintlab.tools.accounts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osintlab.support.HttpSupport;
import com.osintlab.support.UpstreamException;
import com.osintlab.tools.OsintTool;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Instagram profile and recent posts (captions, locations, hashtags) - the data SoIG used to
 * collect. SoIG itself scraped page markup Instagram removed in 2022, so this uses the web
 * profile API instead, authenticated with the configured sessionid.
 */
@Component
public class InstagramProfileTool implements OsintTool {

    private static final String WEB_APP_ID = "936619743392459";
    private static final Pattern HASHTAG = Pattern.compile("#([\\p{L}\\p{N}_]+)");

    private final InstagramSession session;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    public InstagramProfileTool(InstagramSession session, ObjectMapper mapper) {
        this.session = session;
        this.mapper = mapper;
    }

    @Override
    public String id() {
        return "instagram";
    }

    @Override
    public int maxConcurrent() {
        return 1;
    }

    @Override
    public Duration cacheTtl() {
        return Duration.ofHours(6);
    }

    @Override
    public Object run(String target) throws Exception {
        String username = UsernameTarget.of(target).username();
        HttpRequest req = HttpRequest.newBuilder(
                        URI.create("https://i.instagram.com/api/v1/users/web_profile_info/?username=" + username))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", HttpSupport.USER_AGENT)
                .header("x-ig-app-id", WEB_APP_ID)
                .header("Cookie", "sessionid=" + session.require())
                .GET().build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 404) throw new UpstreamException("No Instagram account named " + username);
        if (resp.statusCode() == 429) throw new UpstreamException("Instagram is rate-limiting - wait a few minutes");
        if (resp.statusCode() >= 400) throw new UpstreamException("Instagram returned HTTP " + resp.statusCode());

        JsonNode user;
        try {
            user = mapper.readTree(resp.body()).path("data").path("user");
        } catch (Exception e) {
            throw new UpstreamException("Instagram returned an unexpected page - the sessionid may be expired");
        }
        if (user.isMissingNode() || user.isNull()) throw new UpstreamException("No Instagram account named " + username);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("username", user.path("username").asText());
        out.put("profileUrl", "https://www.instagram.com/" + user.path("username").asText() + "/");
        out.put("id", user.path("id").asText());
        out.put("fullName", user.path("full_name").asText(null));
        out.put("biography", user.path("biography").asText(null));
        out.put("externalUrl", user.path("external_url").asText(null));
        out.put("followers", user.path("edge_followed_by").path("count").asLong());
        out.put("following", user.path("edge_follow").path("count").asLong());
        out.put("posts", user.path("edge_owner_to_timeline_media").path("count").asLong());
        out.put("private", user.path("is_private").asBoolean());
        out.put("verified", user.path("is_verified").asBoolean());
        out.put("business", user.path("is_business_account").asBoolean());
        out.put("category", user.path("category_name").asText(null));
        out.put("businessEmail", user.path("business_email").asText(null));
        out.put("businessPhone", user.path("business_phone_number").asText(null));
        out.put("profilePicture", user.path("profile_pic_url_hd").asText(null));

        List<Map<String, Object>> posts = new ArrayList<>();
        Map<String, Integer> tags = new LinkedHashMap<>();
        Map<String, Integer> locations = new LinkedHashMap<>();
        for (JsonNode edge : user.path("edge_owner_to_timeline_media").path("edges")) {
            JsonNode n = edge.path("node");
            String caption = n.path("edge_media_to_caption").path("edges").path(0).path("node").path("text").asText("");
            String location = n.path("location").path("name").asText(null);
            Map<String, Object> post = new LinkedHashMap<>();
            post.put("date", Instant.ofEpochSecond(n.path("taken_at_timestamp").asLong()).toString().substring(0, 10));
            post.put("url", "https://www.instagram.com/p/" + n.path("shortcode").asText() + "/");
            post.put("likes", n.path("edge_liked_by").path("count").asLong());
            post.put("comments", n.path("edge_media_to_comment").path("count").asLong());
            post.put("location", location);
            post.put("caption", caption.length() > 200 ? caption.substring(0, 200) + "..." : caption);
            posts.add(post);

            Matcher m = HASHTAG.matcher(caption);
            while (m.find()) tags.merge(m.group(1).toLowerCase(), 1, Integer::sum);
            if (location != null) locations.merge(location, 1, Integer::sum);
        }
        out.put("topHashtags", top(tags));
        out.put("taggedLocations", top(locations));
        out.put("recentPosts", posts);
        return out;
    }

    private static String top(Map<String, Integer> counts) {
        if (counts.isEmpty()) return null;
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(15)
                .map(e -> e.getKey() + " (" + e.getValue() + ")")
                .collect(Collectors.joining(", "));
    }
}
