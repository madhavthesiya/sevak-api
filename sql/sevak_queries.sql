-- ============================================================
--  SEVAK – Service Marketplace
--  IT214 DBMS Project
--  Run AFTER sevak_ddl.sql and sevak_data.sql
--  Schema: sevak
-- ============================================================
SET search_path = sevak;


-- ############################################################
--  GENERAL USER QUERIES
-- ############################################################


-- ============================================================
-- QUERY 1: Search Providers by Service Name
-- Query Text: A customer searches for "Cleaning" — find all
--   verified providers offering that service with their city,
--   rating, and price.
-- TECHNIQUES: JOIN (5 tables), ILIKE, ORDER BY
-- ============================================================
SELECT
    sp.provider_id,
    u.email                         AS provider_email,
    ci.city_name,
    s.service_name,
    s.base_price,
    sp.avg_rating,
    sp.experience_years
FROM services s
JOIN provider_services ps ON s.service_id    = ps.service_id
JOIN service_providers sp ON ps.provider_id  = sp.provider_id
JOIN users             u  ON sp.user_id      = u.user_id
JOIN cities            ci ON sp.city_id      = ci.city_id
WHERE s.service_name ILIKE '%cleaning%'
  AND sp.is_active = TRUE
  AND sp.verification_status = 'verified'
ORDER BY sp.avg_rating DESC NULLS LAST;


-- ============================================================
-- QUERY 2: Compare Providers for a Service in a City
-- Query Text: "I want a plumber in Mumbai — compare all
--   available providers with their rating, experience, price."
-- TECHNIQUES: JOIN (6 tables), multiple WHERE conditions
-- ============================================================
SELECT
    sp.provider_id,
    u.email                         AS provider_email,
    sp.experience_years,
    sp.avg_rating,
    sp.custom_price                 AS provider_rate,
    s.service_name,
    s.base_price                    AS catalogue_price
FROM provider_services ps
JOIN service_providers sp ON ps.provider_id  = sp.provider_id
JOIN services          s  ON ps.service_id   = s.service_id
JOIN categories        cat ON s.category_id  = cat.category_id
JOIN users             u  ON sp.user_id      = u.user_id
JOIN cities            ci ON sp.city_id      = ci.city_id
WHERE cat.category_name = 'Plumbing'
  AND ci.city_name      = 'Mumbai'
  AND sp.is_active      = TRUE
ORDER BY sp.avg_rating DESC NULLS LAST;


-- ############################################################
--  CUSTOMER QUERIES
-- ############################################################


-- ============================================================
-- QUERY 3: Booking Detail — Itemized Breakdown
-- Query Text: Customer clicks on Booking #7 to see exact
--   services, variants, quantities, and line totals.
-- TECHNIQUES: JOIN (3 tables), LEFT JOIN, computed column
-- ============================================================
SELECT
    b.booking_id,
    s.service_name,
    sv.variant_name,
    bi.quantity,
    bi.unit_price,
    (bi.quantity * bi.unit_price)    AS line_total
FROM booking_items bi
JOIN bookings         b  ON bi.booking_id = b.booking_id
JOIN services         s  ON bi.service_id = s.service_id
LEFT JOIN service_variants sv ON bi.variant_id = sv.variant_id
WHERE b.booking_id = 7;


-- ============================================================
-- QUERY 4: Customer Spending Summary
-- Query Text: How much has Customer #1 spent in total?
--   Show total bookings, completed, cancelled, total amount.
-- TECHNIQUES: GROUP BY, CASE, COALESCE, SUM, COUNT
-- ============================================================
SELECT
    cu.customer_id,
    cu.name,
    COUNT(b.booking_id)                                     AS total_bookings,
    COUNT(CASE WHEN b.status = 'completed' THEN 1 END)     AS completed,
    COUNT(CASE WHEN b.status = 'cancelled' THEN 1 END)     AS cancelled,
    COALESCE(SUM(CASE WHEN b.status = 'completed'
                 THEN b.total_amount ELSE 0 END), 0)       AS total_spent
FROM customers cu
JOIN bookings b ON cu.customer_id = b.customer_id
WHERE cu.customer_id = 1
GROUP BY cu.customer_id, cu.name;


-- ============================================================
-- QUERY 5: Search Providers by City, Category, and Availability
-- Query Text: "Find me an electrician in Ahmedabad available
--   on Monday" — show all matching providers with their services.
-- TECHNIQUES: JOIN (7 tables), STRING_AGG, GROUP BY
-- ============================================================
SELECT
    sp.provider_id,
    u.email                            AS provider_email,
    sp.avg_rating,
    sp.experience_years,
    pa.start_time,
    pa.end_time,
    STRING_AGG(s.service_name, ', ')   AS offered_services
FROM provider_availability pa
JOIN service_providers sp ON pa.provider_id = sp.provider_id
JOIN users             u  ON sp.user_id     = u.user_id
JOIN cities            ci ON sp.city_id     = ci.city_id
JOIN provider_services ps ON sp.provider_id = ps.provider_id
JOIN services          s  ON ps.service_id  = s.service_id
JOIN categories        cat ON s.category_id = cat.category_id
WHERE ci.city_name     = 'Ahmedabad'
  AND cat.category_name = 'Electrical'
  AND pa.day_of_week   = 'Monday'
  AND sp.is_active     = TRUE
GROUP BY sp.provider_id, u.email, sp.avg_rating,
         sp.experience_years, pa.start_time, pa.end_time
ORDER BY sp.avg_rating DESC NULLS LAST;


-- ############################################################
--  SERVICE PROVIDER QUERIES
-- ############################################################


-- ============================================================
-- QUERY 6: Provider Earnings by Payment Method
-- Query Text: Show Provider #1's total earnings broken down
--   by UPI, Credit Card, and Net Banking.
-- TECHNIQUES: JOIN, SUM, CASE (pivot-style aggregation)
-- ============================================================
SELECT
    COUNT(b.booking_id)                                     AS total_jobs,
    SUM(p.amount)                                           AS total_earnings,
    SUM(CASE WHEN p.payment_method = 'UPI'
             THEN p.amount ELSE 0 END)                     AS upi_earnings,
    SUM(CASE WHEN p.payment_method = 'Credit Card'
             THEN p.amount ELSE 0 END)                     AS card_earnings,
    SUM(CASE WHEN p.payment_method = 'Net Banking'
             THEN p.amount ELSE 0 END)                     AS netbanking_earnings
FROM bookings b
JOIN payments p ON b.booking_id = p.booking_id
WHERE b.provider_id = 1
  AND p.status = 'success';


-- ============================================================
-- QUERY 7: Provider Monthly Earnings Report
-- Query Text: Show Provider #1's earnings grouped by month.
-- TECHNIQUES: DATE_TRUNC, GROUP BY, SUM, COUNT
-- ============================================================
SELECT
    DATE_TRUNC('month', b.scheduled_date)                   AS month,
    COUNT(b.booking_id)                                     AS jobs_completed,
    SUM(p.amount)                                           AS monthly_earnings
FROM bookings b
JOIN payments p ON b.booking_id = p.booking_id
WHERE b.provider_id = 1
  AND b.status   = 'completed'
  AND p.status   = 'success'
GROUP BY DATE_TRUNC('month', b.scheduled_date)
ORDER BY month DESC;


-- ============================================================
-- QUERY 8: Provider Rating vs City Average
-- Query Text: Is Provider #1's rating above or below the
--   average of all providers in their city?
-- TECHNIQUES: Derived table (subquery in FROM), CASE, ROUND
-- ============================================================
SELECT
    sp.provider_id,
    u.email                                 AS provider_email,
    ci.city_name,
    sp.avg_rating                           AS my_rating,
    city_avg.city_average,
    CASE
        WHEN sp.avg_rating >= city_avg.city_average
            THEN 'Above Average'
        ELSE 'Below Average'
    END                                     AS comparison
FROM service_providers sp
JOIN users  u  ON sp.user_id  = u.user_id
JOIN cities ci ON sp.city_id  = ci.city_id
JOIN (
    SELECT city_id, ROUND(AVG(avg_rating), 2) AS city_average
    FROM service_providers
    WHERE avg_rating IS NOT NULL
    GROUP BY city_id
) city_avg ON sp.city_id = city_avg.city_id
WHERE sp.provider_id = 1;


-- ############################################################
--  ADMIN / PLATFORM QUERIES
-- ############################################################


-- ============================================================
-- QUERY 9: Revenue by Service Category
-- Query Text: Which service categories generate the most
--   revenue from completed bookings?
-- TECHNIQUES: JOIN (4 tables), GROUP BY, SUM, COUNT DISTINCT
-- ============================================================
SELECT
    cat.category_name,
    COUNT(DISTINCT b.booking_id)     AS total_bookings,
    SUM(bi.unit_price * bi.quantity)  AS total_revenue
FROM categories cat
JOIN services      s  ON cat.category_id = s.category_id
JOIN booking_items bi ON s.service_id    = bi.service_id
JOIN bookings      b  ON bi.booking_id   = b.booking_id
WHERE b.status = 'completed'
GROUP BY cat.category_name
ORDER BY total_revenue DESC;


-- ============================================================
-- QUERY 10: Provider Performance Leaderboard
-- Query Text: Rank all providers by total revenue earned,
--   showing their city, rating, and number of completed jobs.
-- TECHNIQUES: RANK() OVER (Window Function), JOIN (5 tables)
-- ============================================================
SELECT
    sp.provider_id,
    u.email                                              AS provider_email,
    ci.city_name,
    sp.avg_rating,
    COUNT(b.booking_id)                                  AS jobs_completed,
    SUM(p.amount)                                        AS total_revenue,
    RANK() OVER (ORDER BY SUM(p.amount) DESC NULLS LAST) AS revenue_rank
FROM service_providers sp
JOIN users    u  ON sp.user_id     = u.user_id
JOIN cities   ci ON sp.city_id     = ci.city_id
JOIN bookings b  ON sp.provider_id = b.provider_id
JOIN payments p  ON b.booking_id   = p.booking_id
WHERE b.status = 'completed'
  AND p.status = 'success'
GROUP BY sp.provider_id, u.email, ci.city_name, sp.avg_rating
ORDER BY revenue_rank;


-- ============================================================
-- QUERY 11: Areas With the Highest Complaint Frequency
-- Query Text: Which areas/localities have the most complaints?
--   Help admins identify problem zones.
-- TECHNIQUES: JOIN (5 tables), GROUP BY, HAVING, COUNT
-- ============================================================
SELECT
    ci.city_name,
    ar.area_name,
    ar.pincode,
    COUNT(cmp.complaint_id)          AS complaint_count
FROM complaints cmp
JOIN bookings  b  ON cmp.booking_id = b.booking_id
JOIN locations loc ON b.location_id  = loc.location_id
JOIN areas     ar ON loc.area_id    = ar.area_id
JOIN cities    ci ON ar.city_id     = ci.city_id
GROUP BY ci.city_name, ar.area_name, ar.pincode
HAVING COUNT(cmp.complaint_id) >= 1
ORDER BY complaint_count DESC;


-- ============================================================
-- QUERY 12: Coupon Usage and Effectiveness Report
-- Query Text: How many times has each coupon been used?
--   What is the total discount given and remaining uses?
-- TECHNIQUES: LEFT JOIN, GROUP BY, SUM, CASE
-- ============================================================
SELECT
    co.code,
    co.discount_type,
    co.discount_value,
    co.usage_limit,
    COUNT(b.booking_id)                              AS times_used,
    (co.usage_limit - COUNT(b.booking_id))           AS remaining_uses,
    SUM(CASE
        WHEN co.discount_type = 'flat'       THEN co.discount_value
        WHEN co.discount_type = 'percentage' THEN ROUND(b.total_amount * co.discount_value / 100, 2)
        ELSE 0
    END)                                             AS total_discount_given
FROM coupons co
LEFT JOIN bookings b ON co.coupon_id = b.coupon_id
GROUP BY co.coupon_id, co.code, co.discount_type, co.discount_value, co.usage_limit
ORDER BY times_used DESC;


-- ============================================================
-- QUERY 13: Customers Who Never Placed a Booking
-- Query Text: Find registered customers who have never
--   placed a single booking — targets for marketing.
-- TECHNIQUES: LEFT JOIN, IS NULL (Anti-join pattern)
-- ============================================================
SELECT
    cu.customer_id,
    cu.name,
    cu.phone,
    u.email,
    u.status                         AS account_status
FROM customers cu
JOIN users u ON cu.user_id = u.user_id
LEFT JOIN bookings b ON cu.customer_id = b.customer_id
WHERE b.booking_id IS NULL
ORDER BY cu.name;


-- ============================================================
-- QUERY 14: Booking Lifecycle — Time Between Status Changes
-- Query Text: Measure how long it takes for bookings to move
--   from pending → confirmed → in_progress → completed.
-- TECHNIQUES: Self-JOIN, Correlated Subquery, INTERVAL
-- ============================================================
SELECT
    bsl1.booking_id,
    bsl1.status                                       AS from_status,
    bsl2.status                                       AS to_status,
    (bsl2.log_time - bsl1.log_time)                   AS time_taken
FROM booking_status_log bsl1
JOIN booking_status_log bsl2
    ON  bsl1.booking_id = bsl2.booking_id
    AND bsl2.log_time = (
        SELECT MIN(bsl3.log_time)
        FROM booking_status_log bsl3
        WHERE bsl3.booking_id = bsl1.booking_id
          AND bsl3.log_time > bsl1.log_time
    )
ORDER BY bsl1.booking_id, bsl1.log_time;


-- ============================================================
-- QUERY 15: Full Booking Master View — 10+ Table Mega Join
-- Query Text: The ultimate admin report combining every data
--   point: customer, provider, city, area, service, category,
--   items, payment status, review rating, and coupon used.
-- TECHNIQUES: JOIN (10+ tables), LEFT JOIN, COALESCE
-- ============================================================
SELECT
    b.booking_id,
    cu.name                          AS customer_name,
    u_prov.email                     AS provider_email,
    ci.city_name,
    ar.area_name,
    loc.street,
    s.service_name,
    cat.category_name,
    bi.quantity,
    bi.unit_price,
    b.total_amount,
    b.status                         AS booking_status,
    b.scheduled_date,
    COALESCE(p.status, 'no payment') AS payment_status,
    COALESCE(pr.rating::TEXT, '-')   AS provider_rating,
    COALESCE(co.code, 'none')        AS coupon_used
FROM bookings b
JOIN customers          cu     ON b.customer_id  = cu.customer_id
JOIN service_providers  sp     ON b.provider_id  = sp.provider_id
JOIN users              u_prov ON sp.user_id     = u_prov.user_id
JOIN locations          loc    ON b.location_id  = loc.location_id
JOIN areas              ar     ON loc.area_id    = ar.area_id
JOIN cities             ci     ON ar.city_id     = ci.city_id
JOIN booking_items      bi     ON b.booking_id   = bi.booking_id
JOIN services           s      ON bi.service_id  = s.service_id
JOIN categories         cat    ON s.category_id  = cat.category_id
LEFT JOIN payments      p      ON b.booking_id   = p.booking_id
LEFT JOIN provider_reviews pr  ON b.booking_id   = pr.booking_id
LEFT JOIN coupons       co     ON b.coupon_id    = co.coupon_id
ORDER BY b.booking_id, bi.item_no;


-- ============================================================
--  END OF QUERIES
-- ============================================================
