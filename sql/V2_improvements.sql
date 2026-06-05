-- ============================================================
--  SEVAK – V2 Improvements Migration
--  Run this AFTER the initial DDL and seed data are loaded.
--  This script is idempotent — safe to run multiple times.
-- ============================================================

SET search_path = sevak;


-- ============================================================
--  1. MATERIALIZED VIEW: Provider Leaderboard
-- ============================================================
-- PURPOSE: Pre-computes the expensive RANK() window function
--          query that joins 4 tables. The API reads from this
--          view in O(1) instead of running the full JOIN on
--          every request.
--
-- REFRESH: Call POST /api/admin/refresh-views or run:
--          REFRESH MATERIALIZED VIEW CONCURRENTLY mv_provider_leaderboard;
-- ============================================================

DROP MATERIALIZED VIEW IF EXISTS mv_provider_leaderboard;

CREATE MATERIALIZED VIEW mv_provider_leaderboard AS
SELECT
    RANK() OVER (ORDER BY sp.avg_rating DESC NULLS LAST,
                 COUNT(b.booking_id) DESC)       AS rank,
    sp.provider_id,
    u.email,
    ci.city_name,
    sp.avg_rating,
    COUNT(b.booking_id)                          AS jobs_completed,
    COALESCE(SUM(b.total_amount), 0)             AS total_revenue
FROM service_providers sp
JOIN users u    ON sp.user_id     = u.user_id
JOIN cities ci  ON sp.city_id     = ci.city_id
LEFT JOIN bookings b ON sp.provider_id = b.provider_id
                    AND b.status = 'completed'
WHERE sp.is_active = TRUE
GROUP BY sp.provider_id, u.email, ci.city_name, sp.avg_rating
ORDER BY rank;

-- Unique index required for REFRESH CONCURRENTLY
CREATE UNIQUE INDEX IF NOT EXISTS idx_mv_leaderboard_provider
    ON mv_provider_leaderboard(provider_id);


-- ============================================================
--  2. MATERIALIZED VIEW: City Revenue Summary
-- ============================================================
-- PURPOSE: Pre-computes revenue analytics per city. Avoids
--          joining 5 tables (cities → areas → locations →
--          bookings → payments) on every admin dashboard load.
-- ============================================================

DROP MATERIALIZED VIEW IF EXISTS mv_city_revenue_summary;

CREATE MATERIALIZED VIEW mv_city_revenue_summary AS
SELECT
    ci.city_name,
    COUNT(DISTINCT b.booking_id)                 AS total_bookings,
    COUNT(DISTINCT b.customer_id)                AS unique_customers,
    COALESCE(SUM(p.amount), 0)                   AS total_revenue,
    ROUND(COALESCE(AVG(p.amount), 0), 2)         AS avg_booking_value
FROM cities ci
JOIN areas ar       ON ci.city_id       = ar.city_id
JOIN locations loc  ON ar.area_id       = loc.area_id
JOIN bookings b     ON loc.location_id  = b.location_id
LEFT JOIN payments p ON b.booking_id    = p.booking_id
                    AND p.status = 'success'
GROUP BY ci.city_name
ORDER BY total_revenue DESC;

CREATE UNIQUE INDEX IF NOT EXISTS idx_mv_city_revenue
    ON mv_city_revenue_summary(city_name);


-- ============================================================
--  3. ENHANCED TRIGGER: Auto-Update Provider avg_rating
-- ============================================================
-- CHANGE: Previously fired only on INSERT. Now fires on
--         INSERT, UPDATE, and DELETE — covering the full
--         review lifecycle.
--
-- WHY: If a customer edits their rating from 5→2, or deletes
--       a review entirely, the provider's avg_rating must
--       be recalculated. The old trigger would leave stale
--       data in those scenarios.
-- ============================================================

CREATE OR REPLACE FUNCTION fn_update_provider_avg_rating()
RETURNS TRIGGER AS $$
DECLARE
    v_provider_id INT;
    v_new_avg     NUMERIC(3,2);
BEGIN
    -- Get provider_id from the affected booking.
    -- Use NEW for INSERT/UPDATE, OLD for DELETE (NEW is NULL on DELETE).
    IF TG_OP = 'DELETE' THEN
        SELECT b.provider_id INTO v_provider_id
        FROM bookings b
        WHERE b.booking_id = OLD.booking_id;
    ELSE
        SELECT b.provider_id INTO v_provider_id
        FROM bookings b
        WHERE b.booking_id = NEW.booking_id;
    END IF;

    -- Recalculate average from ALL remaining reviews for this provider.
    -- Returns NULL if no reviews remain (which correctly nullifies avg_rating).
    SELECT ROUND(AVG(pr.rating), 2) INTO v_new_avg
    FROM provider_reviews pr
    JOIN bookings b ON pr.booking_id = b.booking_id
    WHERE b.provider_id = v_provider_id;

    UPDATE service_providers
    SET avg_rating = v_new_avg
    WHERE provider_id = v_provider_id;

    RAISE NOTICE 'Provider % avg_rating updated to % (trigger: %)',
        v_provider_id, v_new_avg, TG_OP;

    -- Must return OLD for DELETE, NEW for INSERT/UPDATE
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Drop the old INSERT-only trigger and replace with full lifecycle trigger
DROP TRIGGER IF EXISTS trg_after_review_insert ON provider_reviews;

CREATE TRIGGER trg_after_review_change
AFTER INSERT OR UPDATE OR DELETE ON provider_reviews
FOR EACH ROW
EXECUTE FUNCTION fn_update_provider_avg_rating();


-- ============================================================
--  VERIFICATION
-- ============================================================
-- Run these to confirm everything was created successfully:
--
-- SELECT * FROM mv_provider_leaderboard;
-- SELECT * FROM mv_city_revenue_summary;
-- SELECT tgname, tgevent FROM pg_trigger WHERE tgrelid = 'provider_reviews'::regclass;
-- ============================================================
