package com.osintlab.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/** Shared HTTP client for tools that call web services. */
@Component
public class HttpSupport {

    public static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36";
    private static final int DEFAULT_MAX_BYTES = 2_000_000;

    public record Fetched(int status, URI uri, java.net.http.HttpHeaders headers, String body) {
    }

    private final HttpClient following = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final HttpClient nonFollowing = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final ObjectMapper mapper;

    public HttpSupport(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Fetched get(String url, Duration timeout, String accept) throws IOException, InterruptedException {
        return get(URI.create(url), timeout, accept, DEFAULT_MAX_BYTES, true);
    }

    /** GET with a body size cap. {@code follow=false} returns redirects as-is. */
    public Fetched get(URI uri, Duration timeout, String accept, int maxBytes, boolean follow)
            throws IOException, InterruptedException {
        return get(uri, timeout, accept, maxBytes, follow, Map.of());
    }

    /** As above, with extra request headers (a "User-Agent" entry replaces the default one). */
    public Fetched get(URI uri, Duration timeout, String accept, int maxBytes, boolean follow,
                       Map<String, String> headers) throws IOException, InterruptedException {
        HttpRequest.Builder req = HttpRequest.newBuilder(uri).timeout(timeout)
                .header("User-Agent", headers.getOrDefault("User-Agent", USER_AGENT)).GET();
        if (accept != null) req.header("Accept", accept);
        headers.forEach((name, value) -> {
            if (!name.equalsIgnoreCase("User-Agent")) req.header(name, value);
        });
        HttpResponse<InputStream> resp = (follow ? following : nonFollowing)
                .send(req.build(), HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = resp.body()) {
            String body = new String(in.readNBytes(maxBytes), StandardCharsets.UTF_8);
            return new Fetched(resp.statusCode(), resp.uri(), resp.headers(), body);
        }
    }

    public JsonNode getJson(String url, Duration timeout) throws IOException, InterruptedException {
        return getJson(url, timeout, "application/json", 50_000_000);
    }

    /** JSON GET for "look this up" APIs: returns null when the record doesn't exist (HTTP 404). */
    public JsonNode getJsonOrNull(String url, Duration timeout) throws IOException, InterruptedException {
        return getJsonOrNull(url, timeout, Map.of(), Set.of(404));
    }

    /**
     * JSON GET that returns null for any of {@code missing}, the status codes the service uses for
     * "no such record" (404 for most, 400 for some). Other errors throw {@link UpstreamException}.
     */
    public JsonNode getJsonOrNull(String url, Duration timeout, Map<String, String> headers, Set<Integer> missing)
            throws IOException, InterruptedException {
        String host = URI.create(url).getHost();
        Fetched f = get(URI.create(url), timeout, "application/json", 20_000_000, true, headers);
        if (missing.contains(f.status())) return null;
        if (f.status() == 429 || (f.status() == 403 && f.body().toLowerCase().contains("rate limit"))) {
            throw new UpstreamException(host + " rate limit reached, try again later");
        }
        if (f.status() >= 400) throw new UpstreamException(host + " returned HTTP " + f.status());
        try {
            return mapper.readTree(f.body());
        } catch (JsonProcessingException e) {
            throw new UpstreamException(host + " returned an unreadable response (not JSON)");
        }
    }

    public JsonNode getJson(String url, Duration timeout, String accept, int maxBytes)
            throws IOException, InterruptedException {
        String host = URI.create(url).getHost();
        Fetched f = get(URI.create(url), timeout, accept, maxBytes, true);
        if (f.status() == 404) throw new UpstreamException("No record found (HTTP 404 from " + host + ")");
        if (f.status() == 429) throw new UpstreamException(host + " is rate-limiting requests, try again later");
        if (f.status() >= 400) throw new UpstreamException(host + " returned HTTP " + f.status());
        try {
            return mapper.readTree(f.body());
        } catch (JsonProcessingException e) {
            throw new UpstreamException(host + " returned an unreadable response (too large or not JSON)");
        }
    }
}
