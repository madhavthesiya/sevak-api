package com.sevak.api.controller;

import com.sevak.api.repository.GeneralRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/general")
@Tag(name = "1. General", description = "Browse services, search providers, check availability")
public class GeneralController {

    private final GeneralRepository repo;

    public GeneralController(GeneralRepository repo) {
        this.repo = repo;
    }

    @GetMapping("/services")
    @Operation(summary = "Browse all services", description = "List all services grouped by category with base prices")
    public ResponseEntity<List<Map<String, Object>>> getAllServices() {
        return ResponseEntity.ok(repo.getAllServices());
    }

    @GetMapping("/services/search")
    @Operation(summary = "Search providers by service",
            description = "Find active providers offering a service matching the keyword. Uses 5-table JOIN with ILIKE.")
    public ResponseEntity<List<Map<String, Object>>> searchProviders(
            @Parameter(description = "Service name keyword (e.g. cleaning, fan, painting)")
            @RequestParam String q) {
        return ResponseEntity.ok(repo.searchProviders(q));
    }

    @GetMapping("/providers/top")
    @Operation(summary = "Top 5 rated providers",
            description = "Returns the top 5 highest-rated verified providers across all cities")
    public ResponseEntity<List<Map<String, Object>>> getTopProviders() {
        return ResponseEntity.ok(repo.getTopProviders());
    }

    @GetMapping("/providers/available")
    @Operation(summary = "Providers available on a day",
            description = "List all active providers available on the specified day with their time slots")
    public ResponseEntity<List<Map<String, Object>>> getAvailableProviders(
            @Parameter(description = "Day of week (e.g. Monday, Tuesday)")
            @RequestParam String day) {
        return ResponseEntity.ok(repo.getAvailableProviders(day));
    }
}
