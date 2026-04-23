package com.sevak.api.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Map;

@Repository
public class BookingActionRepository {

    private final JdbcTemplate jdbc;

    public BookingActionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Cancel Booking (Calls Stored Procedure) ─────────────
    public Map<String, Object> cancelBooking(int bookingId, String reason) {
        try {
            jdbc.update("CALL sp_cancel_booking(?, ?)", bookingId, reason);
            return Map.of(
                    "success", true,
                    "message", "Booking " + bookingId + " cancelled successfully",
                    "booking_id", bookingId
            );
        } catch (Exception e) {
            return Map.of(
                    "success", false,
                    "message", e.getMessage(),
                    "booking_id", bookingId
            );
        }
    }

    // ── Confirm Booking (Calls Stored Procedure) ────────────
    public Map<String, Object> confirmBooking(int bookingId) {
        try {
            jdbc.update("CALL sp_confirm_booking(?)", bookingId);
            return Map.of(
                    "success", true,
                    "message", "Booking " + bookingId + " confirmed successfully",
                    "booking_id", bookingId
            );
        } catch (Exception e) {
            return Map.of(
                    "success", false,
                    "message", e.getMessage(),
                    "booking_id", bookingId
            );
        }
    }
}
