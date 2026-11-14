-- ============================================================
--  SEVAK: leaderboard view and materialized views (repeatable)
--  Runs before the seed file, which refreshes the materialized
--  views once the data is loaded.
-- ============================================================

DROP MATERIALIZED VIEW IF EXISTS mv_provider_leaderboard;
DROP MATERIALIZED VIEW IF EXISTS mv_city_revenue_summary;
DROP MATERIALIZED VIEW IF EXISTS mv_category_revenue;
DROP VIEW IF EXISTS v_provider_leaderboard;

-- One definition for the live leaderboard and its materialized copy.
-- weighted_rating is a Bayesian average: with few reviews a provider stays near the
-- platform average, so one 5-star review can't beat fifty excellent ones.
-- 5 is how many reviews it takes before a provider's own average starts to dominate.
CREATE VIEW v_provider_leaderboard AS
WITH platform AS (
    SELECT AVG(rating) AS avg_rating FROM provider_reviews
),
-- Completed jobs are counted per provider first, then joined. A booking has at most one successful
-- payment, so COUNT(*) needs no DISTINCT; the old COUNT(DISTINCT) sorted a million rows on disk.
jobs AS (
    SELECT b.provider_id, COUNT(*) AS jobs_completed, COALESCE(SUM(p.amount), 0) AS total_revenue
    FROM bookings b
    LEFT JOIN payments p ON p.booking_id = b.booking_id AND p.status = 'success'
    WHERE b.status = 'completed'
    GROUP BY b.provider_id
),
stats AS (
    SELECT sp.provider_id,
           u.email,
           ci.city_name,
           sp.avg_rating,
           sp.review_count,
           CASE WHEN sp.review_count > 0 THEN
               ROUND((5 * pl.avg_rating + sp.avg_rating * sp.review_count) / (5 + sp.review_count), 2)
           END                              AS weighted_rating,
           -- Unrounded, so search can rank by it exactly as it did when it computed the score itself.
           CASE WHEN sp.review_count > 0 THEN
               (5 * pl.avg_rating + sp.avg_rating * sp.review_count) / (5 + sp.review_count)
           END                              AS weighted_score,
           COALESCE(j.jobs_completed, 0)    AS jobs_completed,
           COALESCE(j.total_revenue, 0)     AS total_revenue
    FROM service_providers sp
    JOIN users u   ON sp.user_id = u.user_id
    JOIN cities ci ON sp.city_id = ci.city_id
    CROSS JOIN platform pl
    LEFT JOIN jobs j ON j.provider_id = sp.provider_id
    WHERE sp.is_active = TRUE
)
SELECT RANK() OVER (ORDER BY weighted_rating DESC NULLS LAST, jobs_completed DESC) AS rank,
       provider_id, email, city_name, avg_rating, review_count, weighted_rating, jobs_completed, total_revenue,
       weighted_score
FROM stats;

CREATE MATERIALIZED VIEW mv_provider_leaderboard AS
SELECT * FROM v_provider_leaderboard ORDER BY rank, provider_id;

CREATE UNIQUE INDEX idx_mv_leaderboard_provider ON mv_provider_leaderboard (provider_id);

CREATE MATERIALIZED VIEW mv_city_revenue_summary AS
SELECT
    ci.city_name,
    COUNT(DISTINCT b.booking_id)         AS total_bookings,
    COUNT(DISTINCT b.customer_id)        AS unique_customers,
    COALESCE(SUM(p.amount), 0)           AS total_revenue,
    ROUND(COALESCE(AVG(p.amount), 0), 2) AS avg_booking_value
FROM cities ci
JOIN areas ar       ON ci.city_id      = ar.city_id
JOIN locations loc  ON ar.area_id      = loc.area_id
JOIN bookings b     ON loc.location_id = b.location_id
LEFT JOIN payments p ON b.booking_id   = p.booking_id AND p.status = 'success'
GROUP BY ci.city_name
ORDER BY total_revenue DESC;

CREATE UNIQUE INDEX idx_mv_city_revenue ON mv_city_revenue_summary (city_name);

-- Revenue by category for the admin chart. Splitting every payment across 2 million booking items took
-- 4.7 s at 1M bookings, close to the app's 5 s query limit, so the result is stored and refreshed with the
-- views above. The definition is AdminRepository.REVENUE_SQL; a test checks they agree.
-- The app refreshes it when it starts, because the demo data is loaded after this file runs.
CREATE MATERIALIZED VIEW mv_category_revenue AS
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
GROUP BY c.category_name;

CREATE UNIQUE INDEX idx_mv_category_revenue ON mv_category_revenue (category_name);
