package com.sevak.api.controller;

import com.sevak.api.repository.CustomerRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/customers")
@Tag(name = "2. Customer", description = "Bookings, payments, spending summary, saved addresses")
public class CustomerController {

    private final CustomerRepository repo;

    public CustomerController(CustomerRepository repo) {
        this.repo = repo;
    }

    @GetMapping("/{id}/bookings")
    @Operation(summary = "My bookings", description = "All bookings for a customer ordered by date descending")
    public ResponseEntity<List<Map<String, Object>>> getBookings(
            @Parameter(description = "Customer ID") @PathVariable int id) {
        return ResponseEntity.ok(repo.getBookings(id));
    }

    @GetMapping("/{customerId}/bookings/{bookingId}/items")
    @Operation(summary = "Booking items", description = "Itemized breakdown of a booking with line totals. Uses LEFT JOIN for optional variants.")
    public ResponseEntity<List<Map<String, Object>>> getBookingItems(
            @PathVariable int customerId,
            @Parameter(description = "Booking ID") @PathVariable int bookingId) {
        return ResponseEntity.ok(repo.getBookingItems(bookingId));
    }

    @GetMapping("/{id}/payments")
    @Operation(summary = "Payment history", description = "All payments for a customer's bookings")
    public ResponseEntity<List<Map<String, Object>>> getPayments(
            @Parameter(description = "Customer ID") @PathVariable int id) {
        return ResponseEntity.ok(repo.getPayments(id));
    }

    @GetMapping("/{id}/spending")
    @Operation(summary = "Spending summary",
            description = "Aggregate spending stats: total bookings, completed/cancelled/pending counts, total spent, avg per booking. Uses CASE, SUM, COUNT, GROUP BY.")
    public ResponseEntity<List<Map<String, Object>>> getSpendingSummary(
            @Parameter(description = "Customer ID") @PathVariable int id) {
        return ResponseEntity.ok(repo.getSpendingSummary(id));
    }

    @GetMapping("/{id}/addresses")
    @Operation(summary = "Saved addresses",
            description = "Customer's saved addresses via 4-table JOIN: customer_addresses → locations → areas → cities. BCNF-compliant schema design.")
    public ResponseEntity<List<Map<String, Object>>> getAddresses(
            @Parameter(description = "Customer ID") @PathVariable int id) {
        return ResponseEntity.ok(repo.getAddresses(id));
    }
}
