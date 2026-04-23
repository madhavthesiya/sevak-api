package com.sevak.api.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public class GeneralRepository {

    private final JdbcTemplate jdbc;

    public GeneralRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Browse All Services by Category ──────────────────────
    public List<Map<String, Object>> getAllServices() {
        return jdbc.queryForList("""
                SELECT cat.category_name, s.service_id, s.service_name,
                       s.base_price, s.description
                FROM services s
                JOIN categories cat ON s.category_id = cat.category_id
                ORDER BY cat.category_name, s.base_price
                """);
    }

    // ── Search Providers by Service Keyword ──────────────────
    public List<Map<String, Object>> searchProviders(String keyword) {
        return jdbc.queryForList("""
                SELECT sp.provider_id, u.email AS provider_email, ci.city_name,
                       s.service_name, s.base_price, sp.avg_rating, sp.experience_years
                FROM services s
                JOIN provider_services ps ON s.service_id = ps.service_id
                JOIN service_providers sp ON ps.provider_id = sp.provider_id
                JOIN users u ON sp.user_id = u.user_id
                JOIN cities ci ON sp.city_id = ci.city_id
                WHERE s.service_name ILIKE ? AND sp.is_active = TRUE
                ORDER BY sp.avg_rating DESC NULLS LAST
                """, "%" + keyword + "%");
    }

    // ── Top 5 Highest-Rated Providers ────────────────────────
    public List<Map<String, Object>> getTopProviders() {
        return jdbc.queryForList("""
                SELECT sp.provider_id, u.email, ci.city_name,
                       sp.avg_rating, sp.experience_years
                FROM service_providers sp
                JOIN users u ON sp.user_id = u.user_id
                JOIN cities ci ON sp.city_id = ci.city_id
                WHERE sp.is_active = TRUE AND sp.verification_status = 'verified'
                ORDER BY sp.avg_rating DESC NULLS LAST
                LIMIT 5
                """);
    }

    // ── Providers Available on a Given Day ───────────────────
    public List<Map<String, Object>> getAvailableProviders(String day) {
        return jdbc.queryForList("""
                SELECT sp.provider_id, u.email, pa.day_of_week,
                       pa.start_time, pa.end_time
                FROM provider_availability pa
                JOIN service_providers sp ON pa.provider_id = sp.provider_id
                JOIN users u ON sp.user_id = u.user_id
                WHERE pa.day_of_week = ? AND sp.is_active = TRUE
                ORDER BY pa.start_time
                """, day);
    }
}
