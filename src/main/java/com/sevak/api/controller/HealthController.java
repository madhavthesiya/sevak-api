package com.sevak.api.controller;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Health check endpoint — used by DigitalOcean/Docker to verify the app is alive.
 * Also useful for uptime monitoring services.
 */
@RestController
public class HealthController {

    private final JdbcTemplate jdbc;

    public HealthController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/health")
    @Hidden // Hide from Swagger — internal use only
    public ResponseEntity<Map<String, Object>> health() {
        String dbStatus;
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            dbStatus = "connected";
        } catch (Exception e) {
            dbStatus = "disconnected";
        }

        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "timestamp", Instant.now().toString(),
                "database", dbStatus,
                "version", "1.0.0"
        ));
    }
}
