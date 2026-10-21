package com.bellgado.logistics_ted.web;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Resolves a URL redirect chain server-side (used for Google Maps short links
 * that cannot be followed from the browser due to CORS).
 */
@RestController
public class UrlResolveController {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @GetMapping("/api/resolve-url")
    public ResponseEntity<Map<String, String>> resolve(@RequestParam String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(6))
                    .header("User-Agent", "Mozilla/5.0")
                    .GET()
                    .build();
            // We only need the final URI after redirects — discard the body
            HttpResponse<Void> res = CLIENT.send(req, HttpResponse.BodyHandlers.discarding());
            return ResponseEntity.ok(Map.of("url", res.uri().toString()));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("url", url));
        }
    }
}
