package com.sevak.api.controller;

import com.sevak.api.repository.ProviderRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/providers")
@Tag(name = "3. Provider", description = "Jobs, earnings, reviews, rating comparison")
public class ProviderController {

    private final ProviderRepository repo;

    public ProviderController(ProviderRepository repo) {
        this.repo = repo;
    }

    @GetMapping("/{id}/pending")
    @Operation(summary = "Pending jobs",
            description = "All pending jobs for a provider with customer name and service location")
    public ResponseEntity<List<Map<String, Object>>> getPendingJobs(
            @Parameter(description = "Provider ID") @PathVariable int id) {
        return ResponseEntity.ok(repo.getPendingJobs(id));
    }

    @GetMapping("/{id}/completed")
    @Operation(summary = "Completed jobs",
            description = "All completed jobs with payment status. Uses LEFT JOIN for optional payments.")
    public ResponseEntity<List<Map<String, Object>>> getCompletedJobs(
            @Parameter(description = "Provider ID") @PathVariable int id) {
        return ResponseEntity.ok(repo.getCompletedJobs(id));
    }

    @GetMapping("/{id}/earnings")
    @Operation(summary = "Earnings summary",
            description = "Total earnings grouped by payment method. Uses SUM, COUNT, GROUP BY.")
    public ResponseEntity<List<Map<String, Object>>> getEarnings(
            @Parameter(description = "Provider ID") @PathVariable int id) {
        return ResponseEntity.ok(repo.getEarnings(id));
    }

    @GetMapping("/{id}/reviews")
    @Operation(summary = "Reviews & ratings",
            description = "All reviews received from customers with rating and comment")
    public ResponseEntity<List<Map<String, Object>>> getReviews(
            @Parameter(description = "Provider ID") @PathVariable int id) {
        return ResponseEntity.ok(repo.getReviews(id));
    }

    @GetMapping("/{id}/rating")
    @Operation(summary = "Rating vs city average",
            description = "Compares provider's rating against city average using a DERIVED TABLE (subquery in FROM clause). Uses CASE for comparison label.")
    public ResponseEntity<List<Map<String, Object>>> getRatingComparison(
            @Parameter(description = "Provider ID") @PathVariable int id) {
        return ResponseEntity.ok(repo.getRatingComparison(id));
    }
}
