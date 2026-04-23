package com.sevak.api.controller;

import com.sevak.api.repository.BookingActionRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
@Tag(name = "5. Booking Actions", description = "Cancel or confirm bookings using PostgreSQL stored procedures")
public class BookingActionController {

    private final BookingActionRepository repo;

    public BookingActionController(BookingActionRepository repo) {
        this.repo = repo;
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel a booking",
            description = "Calls the sp_cancel_booking PostgreSQL stored procedure. Handles status update, cancellation record creation, and conditional refund processing within a database transaction.")
    public ResponseEntity<Map<String, Object>> cancelBooking(
            @Parameter(description = "Booking ID") @PathVariable int id,
            @Parameter(description = "Cancellation reason") @RequestParam(defaultValue = "Cancelled via API") String reason) {
        return ResponseEntity.ok(repo.cancelBooking(id, reason));
    }

    @PostMapping("/{id}/confirm")
    @Operation(summary = "Confirm a booking",
            description = "Calls the sp_confirm_booking PostgreSQL stored procedure. Updates booking status and logs the status change.")
    public ResponseEntity<Map<String, Object>> confirmBooking(
            @Parameter(description = "Booking ID") @PathVariable int id) {
        return ResponseEntity.ok(repo.confirmBooking(id));
    }
}
