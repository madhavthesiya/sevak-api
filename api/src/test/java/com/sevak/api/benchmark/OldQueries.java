package com.sevak.api.benchmark;

/**
 * Earlier versions of queries that Phase 6 replaced, copied unchanged from git, so the benchmark can show
 * the before and after on the same data and prove the new versions return the same rows.
 */
final class OldQueries {

    private OldQueries() {}

    /** Revenue before task 0.4 (commit 66f8b1d). Joining items fans out, so payments count twice. */
    static final String REVENUE_FAN_OUT = """
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
            """;

    /** Revenue from task 0.4 until Phase 6: correct, but re-adds every booking's items with a window. */
    static final String REVENUE_WINDOW = """
            WITH item_totals AS (
                SELECT bi.booking_id,
                       s.category_id,
                       SUM(bi.quantity * bi.unit_price) AS cat_amount,
                       SUM(SUM(bi.quantity * bi.unit_price)) OVER (PARTITION BY bi.booking_id) AS booking_items_total
                FROM booking_items bi
                JOIN services s ON s.service_id = bi.service_id
                GROUP BY bi.booking_id, s.category_id
            )
            SELECT c.category_name,
                   COUNT(DISTINCT it.booking_id) AS total_bookings,
                   ROUND(SUM(p.amount * it.cat_amount / NULLIF(it.booking_items_total, 0)), 2) AS total_revenue
            FROM item_totals it
            JOIN payments   p ON p.booking_id  = it.booking_id AND p.status = 'success'
            JOIN categories c ON c.category_id = it.category_id
            GROUP BY c.category_name
            ORDER BY total_revenue DESC, c.category_name
            """;

    /**
     * A revenue rewrite tried in Phase 6 and rejected. It divides by the trigger-guaranteed subtotal instead
     * of re-adding items with a window, and returns identical rows. It looked faster in two noisy runs but
     * was 1.8x slower in the clean one: the planner underestimated the aggregate's rows (55k vs 751k) and
     * chose a parallel plan in which each of three processes rebuilt the same hash table.
     */
    static final String REVENUE_SUBTOTAL_REJECTED = """
            WITH booking_categories AS (
                SELECT bi.booking_id, s.category_id, SUM(bi.quantity * bi.unit_price) AS cat_amount
                FROM booking_items bi
                JOIN services s ON s.service_id = bi.service_id
                GROUP BY bi.booking_id, s.category_id
            )
            SELECT c.category_name,
                   COUNT(*) AS total_bookings,
                   ROUND(SUM(p.amount * bc.cat_amount / NULLIF(b.subtotal, 0)), 2) AS total_revenue
            FROM booking_categories bc
            JOIN payments   p ON p.booking_id  = bc.booking_id AND p.status = 'success'
            JOIN bookings   b ON b.booking_id  = bc.booking_id
            JOIN categories c ON c.category_id = bc.category_id
            GROUP BY c.category_name
            ORDER BY total_revenue DESC, c.category_name
            """;

    /** The leaderboard view until Phase 6: COUNT(DISTINCT) over bookings joined to payments. */
    private static final String LEADERBOARD_VIEW = """
            WITH platform AS (
                SELECT AVG(rating) AS avg_rating FROM provider_reviews
            ),
            stats AS (
                SELECT sp.provider_id,
                       u.email,
                       ci.city_name,
                       sp.avg_rating,
                       sp.review_count,
                       CASE WHEN sp.review_count > 0 THEN
                           ROUND((5 * pl.avg_rating + sp.avg_rating * sp.review_count) / (5 + sp.review_count), 2)
                       END                          AS weighted_rating,
                       COUNT(DISTINCT b.booking_id) AS jobs_completed,
                       COALESCE(SUM(p.amount), 0)   AS total_revenue
                FROM service_providers sp
                JOIN users u   ON sp.user_id = u.user_id
                JOIN cities ci ON sp.city_id = ci.city_id
                CROSS JOIN platform pl
                LEFT JOIN bookings b ON sp.provider_id = b.provider_id AND b.status = 'completed'
                LEFT JOIN payments p ON b.booking_id = p.booking_id AND p.status = 'success'
                WHERE sp.is_active = TRUE
                GROUP BY sp.provider_id, u.email, ci.city_name, sp.avg_rating, sp.review_count, pl.avg_rating
            )
            SELECT RANK() OVER (ORDER BY weighted_rating DESC NULLS LAST, jobs_completed DESC) AS rank,
                   provider_id, email, city_name, avg_rating, review_count, weighted_rating, jobs_completed, total_revenue
            FROM stats
            """;

    /** The admin leaderboard as it read the old view. */
    static final String LEADERBOARD_LIVE = """
            SELECT lb.*, u.full_name AS provider_name
            FROM (%s) lb
            JOIN service_providers sp ON sp.provider_id = lb.provider_id
            JOIN users u ON u.user_id = sp.user_id
            ORDER BY lb.rank, lb.provider_id
            """.formatted(LEADERBOARD_VIEW);

    /** Home page "highest rated" until Phase 6: computed live on every visit. */
    static final String TOP = """
            SELECT lb.provider_id, u.full_name AS provider_name, lb.city_name, lb.avg_rating,
                   lb.review_count, lb.weighted_rating, sp.experience_years
            FROM (%s) lb
            JOIN service_providers sp ON sp.provider_id = lb.provider_id
            JOIN users u ON u.user_id = sp.user_id
            WHERE sp.verification_status = 'verified'
            ORDER BY lb.weighted_rating DESC NULLS LAST, lb.provider_id
            LIMIT :limit
            """.formatted(LEADERBOARD_VIEW);

    /** Search until Phase 6: the platform average was recomputed from every review on every search. */
    static final String SEARCH = """
            WITH platform AS (SELECT AVG(rating) AS avg_rating FROM provider_reviews)
            SELECT sp.provider_id, u.full_name AS provider_name, ci.city_name, s.service_id,
                   s.service_name, s.base_price, ps.price, sp.avg_rating, sp.review_count,
                   sp.experience_years
            FROM provider_services ps
            JOIN services s           ON s.service_id = ps.service_id
            JOIN service_providers sp ON sp.provider_id = ps.provider_id
            JOIN users u              ON u.user_id = sp.user_id
            JOIN cities ci            ON ci.city_id = sp.city_id
            CROSS JOIN platform pl
            WHERE ps.is_active AND sp.is_active AND sp.verification_status = 'verified'
              AND (CAST(:service AS text) IS NULL OR s.service_name ILIKE :service ESCAPE '\\')
              AND (CAST(:city AS text) IS NULL OR LOWER(ci.city_name) = LOWER(:city))
              AND (CAST(:day AS int) IS NULL OR EXISTS (
                    SELECT 1 FROM provider_availability pa
                    WHERE pa.provider_id = sp.provider_id AND pa.day_of_week = CAST(:day AS int)))
            ORDER BY sp.review_count = 0,
                     (5 * pl.avg_rating + COALESCE(sp.avg_rating, 0) * sp.review_count)
                         / (5 + sp.review_count) DESC,
                     sp.provider_id, s.service_id
            LIMIT :limit OFFSET :offset
            """;
}
