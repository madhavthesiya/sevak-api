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
        String dbError = "none";
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            dbStatus = "connected";
        } catch (Exception e) {
            dbStatus = "disconnected";
            dbError = e.getMessage();
        }

        String url = System.getenv("SEVAK_DB_URL");
        String user = System.getenv("SEVAK_DB_USER");

        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "timestamp", Instant.now().toString(),
                "database", dbStatus,
                "db_error", dbError,
                "db_url", url != null ? url.substring(0, Math.min(url.length(), 60)) + "..." : "NOT SET",
                "db_user", user != null ? user : "NOT SET",
                "version", "1.0.0"
        ));
    }
}
