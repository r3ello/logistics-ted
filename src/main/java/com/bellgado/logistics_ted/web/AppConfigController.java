package com.bellgado.logistics_ted.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class AppConfigController {

    @Value("${app.base-url}")
    private String baseUrl;

    /** Public endpoint — returns app config needed by the frontend (no auth required). */
    @GetMapping("/api/public/config")
    public Map<String, String> config() {
        return Map.of("baseUrl", baseUrl);
    }
}
