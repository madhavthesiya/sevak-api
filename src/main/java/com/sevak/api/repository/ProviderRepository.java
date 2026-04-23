package com.sevak.api.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public class ProviderRepository {

    private final JdbcTemplate jdbc;

    public ProviderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Pending Jobs with Location ──────────────────────────
    public List<Map<String, Object>> getPendingJobs(int providerId) {
        return jdbc.queryForList("""
                SELECT b.booking_id, cu.name AS customer, b.scheduled_date,
                       b.scheduled_time, b.total_amount, loc.street, ar.area_name
                FROM bookings b
                JOIN customers cu ON b.customer_id = cu.customer_id
                JOIN locations loc ON b.location_id = loc.location_id
                JOIN areas ar ON loc.area_id = ar.area_id
                WHERE b.provider_id = ? AND b.status = 'pending'
                ORDER BY b.scheduled_date, b.scheduled_time
                """, providerId);
    }

    // ── Completed Jobs ──────────────────────────────────────
    public List<Map<String, Object>> getCompletedJobs(int providerId) {
        return jdbc.queryForList("""
                SELECT b.booking_id, cu.name AS customer, b.scheduled_date,
                       b.total_amount, p.status AS payment_status
                FROM bookings b
                JOIN customers cu ON b.customer_id = cu.customer_id
                LEFT JOIN payments p ON b.booking_id = p.booking_id
                WHERE b.provider_id = ? AND b.status = 'completed'
                ORDER BY b.scheduled_date DESC
                """, providerId);
    }

    // ── Earnings by Payment Method (CASE, SUM, GROUP BY) ────
    public List<Map<String, Object>> getEarnings(int providerId) {
        return jdbc.queryForList("""
                SELECT p.payment_method,
                       COUNT(*)         AS total_payments,
                       SUM(p.amount)    AS total_earned
                FROM payments p
                JOIN bookings b ON p.booking_id = b.booking_id
                WHERE b.provider_id = ? AND p.status = 'success'
                GROUP BY p.payment_method
                ORDER BY total_earned DESC
                """, providerId);
    }

    // ── Reviews & Ratings ───────────────────────────────────
    public List<Map<String, Object>> getReviews(int providerId) {
        return jdbc.queryForList("""
                SELECT pr.rating, pr.comment, cu.name AS customer,
                       b.scheduled_date
                FROM provider_reviews pr
                JOIN bookings b ON pr.booking_id = b.booking_id
                JOIN customers cu ON b.customer_id = cu.customer_id
                WHERE b.provider_id = ?
                ORDER BY b.scheduled_date DESC
                """, providerId);
    }

    // ── Rating vs City Average (Derived Table / Subquery) ───
    public List<Map<String, Object>> getRatingComparison(int providerId) {
        return jdbc.queryForList("""
                SELECT sp.provider_id, u.email, ci.city_name,
                       sp.avg_rating AS my_rating,
                       city_avg.avg_city_rating,
                       CASE
                           WHEN sp.avg_rating > city_avg.avg_city_rating THEN 'Above Average'
                           WHEN sp.avg_rating = city_avg.avg_city_rating THEN 'Average'
                           ELSE 'Below Average'
                       END AS comparison
                FROM service_providers sp
                JOIN users u ON sp.user_id = u.user_id
                JOIN cities ci ON sp.city_id = ci.city_id
                JOIN (
                    SELECT city_id, ROUND(AVG(avg_rating), 2) AS avg_city_rating
                    FROM service_providers
                    WHERE avg_rating IS NOT NULL
                    GROUP BY city_id
                ) city_avg ON sp.city_id = city_avg.city_id
                WHERE sp.provider_id = ?
                """, providerId);
    }
}
