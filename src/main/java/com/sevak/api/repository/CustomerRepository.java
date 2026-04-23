package com.sevak.api.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public class CustomerRepository {

    private final JdbcTemplate jdbc;

    public CustomerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── My Bookings ──────────────────────────────────────────
    public List<Map<String, Object>> getBookings(int customerId) {
        return jdbc.queryForList("""
                SELECT b.booking_id, u.email AS provider_email, b.scheduled_date,
                       b.scheduled_time, b.status, b.total_amount
                FROM bookings b
                JOIN service_providers sp ON b.provider_id = sp.provider_id
                JOIN users u ON sp.user_id = u.user_id
                WHERE b.customer_id = ?
                ORDER BY b.scheduled_date DESC
                """, customerId);
    }

    // ── Booking Items (Itemized) ─────────────────────────────
    public List<Map<String, Object>> getBookingItems(int bookingId) {
        return jdbc.queryForList("""
                SELECT bi.item_no, s.service_name, sv.variant_name,
                       bi.quantity, bi.unit_price,
                       (bi.quantity * bi.unit_price) AS line_total
                FROM booking_items bi
                JOIN services s ON bi.service_id = s.service_id
                LEFT JOIN service_variants sv ON bi.variant_id = sv.variant_id
                WHERE bi.booking_id = ?
                ORDER BY bi.item_no
                """, bookingId);
    }

    // ── Payment History ──────────────────────────────────────
    public List<Map<String, Object>> getPayments(int customerId) {
        return jdbc.queryForList("""
                SELECT b.booking_id, p.payment_method, p.status, p.amount,
                       p.gateway_ref, p.paid_at
                FROM payments p
                JOIN bookings b ON p.booking_id = b.booking_id
                WHERE b.customer_id = ?
                ORDER BY p.paid_at DESC NULLS LAST
                """, customerId);
    }

    // ── Spending Summary (CASE, SUM, COUNT, GROUP BY) ────────
    public List<Map<String, Object>> getSpendingSummary(int customerId) {
        return jdbc.queryForList("""
                SELECT
                    COUNT(*)                                           AS total_bookings,
                    SUM(CASE WHEN status = 'completed' THEN 1 ELSE 0 END)  AS completed,
                    SUM(CASE WHEN status = 'cancelled' THEN 1 ELSE 0 END)  AS cancelled,
                    SUM(CASE WHEN status = 'pending'   THEN 1 ELSE 0 END)  AS pending,
                    SUM(total_amount)                                  AS total_spent,
                    ROUND(AVG(total_amount), 2)                        AS avg_per_booking
                FROM bookings
                WHERE customer_id = ?
                """, customerId);
    }

    // ── Saved Addresses (4-table JOIN via locations) ─────────
    public List<Map<String, Object>> getAddresses(int customerId) {
        return jdbc.queryForList("""
                SELECT loc.location_id, ca.label, loc.street, loc.landmark,
                       ar.area_name, ar.pincode, ci.city_name
                FROM customer_addresses ca
                JOIN locations loc ON ca.location_id = loc.location_id
                JOIN areas ar ON loc.area_id = ar.area_id
                JOIN cities ci ON ar.city_id = ci.city_id
                WHERE ca.customer_id = ?
                ORDER BY ca.label
                """, customerId);
    }
}
