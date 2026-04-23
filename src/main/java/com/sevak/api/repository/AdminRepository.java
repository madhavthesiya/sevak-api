package com.sevak.api.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public class AdminRepository {

    private final JdbcTemplate jdbc;

    public AdminRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Revenue by Category (SUM, GROUP BY, 4-table JOIN) ───
    public List<Map<String, Object>> getRevenueByCat() {
        return jdbc.queryForList("""
                SELECT cat.category_name,
                       COUNT(DISTINCT b.booking_id) AS total_bookings,
                       SUM(p.amount)                AS total_revenue
                FROM payments p
                JOIN bookings b ON p.booking_id = b.booking_id
                JOIN booking_items bi ON b.booking_id = bi.booking_id
                JOIN services s ON bi.service_id = s.service_id
                JOIN categories cat ON s.category_id = cat.category_id
                WHERE p.status = 'success'
                GROUP BY cat.category_name
                ORDER BY total_revenue DESC
                """);
    }

    // ── Provider Leaderboard (RANK() Window Function!) ──────
    public List<Map<String, Object>> getLeaderboard() {
        return jdbc.queryForList("""
                SELECT
                    RANK() OVER (ORDER BY sp.avg_rating DESC NULLS LAST,
                                 COUNT(b.booking_id) DESC)   AS rank,
                    sp.provider_id,
                    u.email,
                    ci.city_name,
                    sp.avg_rating,
                    COUNT(b.booking_id)                      AS jobs_completed,
                    COALESCE(SUM(b.total_amount), 0)         AS total_revenue
                FROM service_providers sp
                JOIN users u ON sp.user_id = u.user_id
                JOIN cities ci ON sp.city_id = ci.city_id
                LEFT JOIN bookings b ON sp.provider_id = b.provider_id
                                    AND b.status = 'completed'
                WHERE sp.is_active = TRUE
                GROUP BY sp.provider_id, u.email, ci.city_name, sp.avg_rating
                ORDER BY rank
                """);
    }

    // ── Complaint Dashboard (STRING_AGG, 5-table JOIN) ──────
    public List<Map<String, Object>> getComplaintDashboard() {
        return jdbc.queryForList("""
                SELECT cmp.status AS complaint_status,
                       COUNT(cmp.complaint_id) AS count,
                       STRING_AGG(DISTINCT ci.city_name, ', ') AS affected_cities
                FROM complaints cmp
                JOIN bookings b ON cmp.booking_id = b.booking_id
                JOIN locations loc ON b.location_id = loc.location_id
                JOIN areas ar ON loc.area_id = ar.area_id
                JOIN cities ci ON ar.city_id = ci.city_id
                GROUP BY cmp.status
                ORDER BY count DESC
                """);
    }
}
