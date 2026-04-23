package com.sevak.api.controller;

import com.sevak.api.repository.AdminRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@Tag(name = "4. Admin Analytics", description = "Revenue reports, provider leaderboard, complaint dashboard")
public class AdminController {

    private final AdminRepository repo;

    public AdminController(AdminRepository repo) {
        this.repo = repo;
    }

    @GetMapping("/revenue")
    @Operation(summary = "Revenue by category",
            description = "Total revenue and booking count per service category. Uses 5-table JOIN with SUM and GROUP BY.")
    public ResponseEntity<List<Map<String, Object>>> getRevenueByCat() {
        return ResponseEntity.ok(repo.getRevenueByCat());
    }

    @GetMapping("/leaderboard")
    @Operation(summary = "Provider leaderboard",
            description = "Provider performance ranking using RANK() Window Function over avg_rating and completed jobs. Demonstrates advanced SQL analytics.")
    public ResponseEntity<List<Map<String, Object>>> getLeaderboard() {
        return ResponseEntity.ok(repo.getLeaderboard());
    }

    @GetMapping("/complaints")
    @Operation(summary = "Complaint dashboard",
            description = "Complaint status summary with affected cities using STRING_AGG aggregate across a 5-table JOIN (complaints → bookings → locations → areas → cities).")
    public ResponseEntity<List<Map<String, Object>>> getComplaintDashboard() {
        return ResponseEntity.ok(repo.getComplaintDashboard());
    }
}
