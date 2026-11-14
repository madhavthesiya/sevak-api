-- ============================================================
--  SEVAK: large benchmark dataset (Phase 6). For benchmarks only; never run it in production.
--
--  Loads about 1M bookings on top of the migrated schema and the demo data. QueryBenchmark runs it
--  in one session; it also works in psql. Every foreign key, CHECK and the double-booking exclusion
--  constraint stay on, so PostgreSQL rejects any row the generator gets wrong. Only the row-by-row
--  triggers are paused, and what they would have written (status log, ratings) is written in bulk.
--
--  Reproducible: random() is seeded, and most choices come from arithmetic on the row number.
--  To load a smaller copy, change the 1.0 in bench_size.
-- ============================================================

SET search_path = sevak;
SET synchronous_commit = off;
SELECT setseed(0.42);

CREATE TEMP TABLE bench_size AS
SELECT round(50000 * scale)::int   AS customers,
       round(5000 * scale)::int    AS providers,
       round(4800 * scale)::int    AS bookable,   -- verified and active; the rest wait or were rejected
       round(1000000 * scale)::int AS bookings
FROM (SELECT 1.0::numeric AS scale) s;

-- Demand is uneven across cities, as it is in real life.
CREATE TEMP TABLE bench_city AS
SELECT c.city_id, c.city_name,
       SUM(v.share) OVER (ORDER BY c.city_id) - v.share AS lo,
       SUM(v.share) OVER (ORDER BY c.city_id)           AS hi
FROM cities c
JOIN (VALUES ('Mumbai', 0.28), ('Bangalore', 0.25), ('Delhi', 0.22), ('Ahmedabad', 0.13), ('Pune', 0.12))
     AS v (city_name, share) ON v.city_name = c.city_name;

CREATE TEMP TABLE bench_names AS
SELECT ARRAY['Aarav','Vivaan','Aditya','Vihaan','Arjun','Sai','Reyansh','Ayaan','Krishna','Ishaan',
             'Ananya','Diya','Saanvi','Aadhya','Myra','Pari','Anika','Navya','Kavya','Riya',
             'Rahul','Priya','Neha','Rohit','Amit','Pooja','Sneha','Karan','Meera','Nikhil'] AS first_names,
       ARRAY['Sharma','Patel','Mehta','Reddy','Singh','Gupta','Desai','Kumar','Nair','Iyer',
             'Joshi','Rao','Verma','Shah','Malhotra','Kapoor','Chopra','Bose','Das','Pillai',
             'Menon','Kulkarni','Jain','Agarwal','Mishra'] AS last_names,
       ARRAY['MG Road','Station Road','Link Road','Nehru Nagar','Gandhi Marg','Park Street',
             'Lake View Road','Temple Street','Market Road','Ring Road'] AS streets;

-- ── Areas: 39 more per city ─────────────────────────────────
INSERT INTO areas (city_id, area_name, pincode)
SELECT c.city_id, c.city_name || ' Sector ' || n, (c.city_id * 100000 + 1000 + n)::text
FROM cities c CROSS JOIN generate_series(1, 39) n;

CREATE TEMP TABLE bench_area AS
SELECT area_id, city_id,
       row_number() OVER (PARTITION BY city_id ORDER BY area_id) - 1 AS idx,
       COUNT(*) OVER (PARTITION BY city_id)                          AS n
FROM areas;

-- ── Customers ───────────────────────────────────────────────
-- Same password as the demo accounts (Demo@1234), so a load test can log in as any of them.
INSERT INTO users (email, password_hash, role, status, full_name, phone, created_at)
SELECT 'customer' || i || '@bench.sevak.test',
       '$2a$10$RwSN2szVDZIaMrJPCx/x9uhtoAG1F.Gk2kf1wiH6HscUjFru5xbz2',
       'customer', 'active',
       nm.first_names[1 + (i * 7) % 30] || ' ' || nm.last_names[1 + (i * 13) % 25],
       '7' || lpad(i::text, 9, '0'),
       now() - interval '1 day' * (600 + i % 300)
FROM bench_size s, bench_names nm, generate_series(1, s.customers) i;

INSERT INTO customers (user_id)
SELECT user_id FROM users WHERE email LIKE 'customer%@bench.sevak.test' ORDER BY user_id;

CREATE TEMP TABLE bench_customer AS
SELECT cu.customer_id, cu.user_id, x.i, c.city_id,
       row_number() OVER (PARTITION BY c.city_id ORDER BY x.i) - 1 AS city_idx
FROM customers cu
JOIN users u ON u.user_id = cu.user_id
CROSS JOIN LATERAL (SELECT substring(u.email FROM '^customer([0-9]+)@')::int AS i) x
CROSS JOIN bench_size s
JOIN bench_city c ON (x.i - 0.5) / s.customers >= c.lo AND (x.i - 0.5) / s.customers < c.hi
WHERE u.email LIKE 'customer%@bench.sevak.test';
CREATE INDEX ON bench_customer (city_id, city_idx);

CREATE TEMP TABLE bench_city_customers AS
SELECT city_id, COUNT(*) AS n FROM bench_customer GROUP BY city_id;

-- ── Addresses: a home for everyone, an office for one in five ─
-- No maps data exists, so each coordinate is a unique point on a grid away from the demo data.
INSERT INTO locations (area_id, street, landmark, latitude, longitude)
SELECT a.area_id, (1 + c.i % 250) || ', ' || nm.streets[1 + c.i % 10], NULL,
       10 + (c.i % 20000) * 0.0005, 70 + (c.i / 20000) * 0.0005
FROM bench_customer c
JOIN bench_area a ON a.city_id = c.city_id AND a.idx = (c.i * 31) % a.n
CROSS JOIN bench_names nm;

INSERT INTO locations (area_id, street, landmark, latitude, longitude)
SELECT a.area_id, (1 + c.i % 90) || ', ' || nm.streets[1 + (c.i + 3) % 10], 'Office block',
       10 + (c.i % 20000) * 0.0005, 71 + (c.i / 20000) * 0.0005
FROM bench_customer c
JOIN bench_area a ON a.city_id = c.city_id AND a.idx = (c.i * 17 + 5) % a.n
CROSS JOIN bench_names nm
WHERE c.i % 5 = 0;

CREATE TEMP TABLE bench_home AS
SELECT c.customer_id, l.location_id
FROM bench_customer c
JOIN locations l ON l.latitude = 10 + (c.i % 20000) * 0.0005 AND l.longitude = 70 + (c.i / 20000) * 0.0005;
CREATE UNIQUE INDEX ON bench_home (customer_id);

INSERT INTO customer_addresses (customer_id, location_id, label, house_no)
SELECT h.customer_id, h.location_id, 'Home', 'Flat ' || (100 + c.i % 900)
FROM bench_home h JOIN bench_customer c USING (customer_id);

INSERT INTO customer_addresses (customer_id, location_id, label, house_no)
SELECT c.customer_id, l.location_id, 'Office', (1 + c.i % 12) || 'th Floor'
FROM bench_customer c
JOIN locations l ON l.latitude = 10 + (c.i % 20000) * 0.0005 AND l.longitude = 71 + (c.i / 20000) * 0.0005
WHERE c.i % 5 = 0;

-- ── Providers ───────────────────────────────────────────────
INSERT INTO users (email, password_hash, role, status, full_name, phone, created_at)
SELECT 'provider' || i || '@bench.sevak.test',
       '$2a$10$RwSN2szVDZIaMrJPCx/x9uhtoAG1F.Gk2kf1wiH6HscUjFru5xbz2',
       'provider', 'active',
       nm.first_names[1 + (i * 11) % 30] || ' ' || nm.last_names[1 + (i * 17) % 25],
       '8' || lpad(i::text, 9, '0'),
       now() - interval '1 day' * (650 + i % 200)
FROM bench_size s, bench_names nm, generate_series(1, s.providers) i;

INSERT INTO service_providers (user_id, city_id, bio, experience_years, verification_status, is_active)
SELECT u.user_id, c.city_id,
       'Experienced professional serving ' || c.city_name || '.',
       1 + (x.i * 7) % 25,
       CASE WHEN x.i <= s.bookable THEN 'verified' WHEN x.i % 3 = 0 THEN 'rejected' ELSE 'pending' END,
       x.i <= s.bookable OR x.i % 3 <> 0
FROM users u
CROSS JOIN LATERAL (SELECT substring(u.email FROM '^provider([0-9]+)@')::int AS i) x
CROSS JOIN bench_size s
JOIN bench_city c ON (x.i - 0.5) / s.providers >= c.lo AND (x.i - 0.5) / s.providers < c.hi
WHERE u.email LIKE 'provider%@bench.sevak.test'
ORDER BY x.i;

CREATE TEMP TABLE bench_provider AS
SELECT sp.provider_id, x.i, sp.city_id, x.i <= s.bookable AS bookable,
       row_number() OVER (PARTITION BY x.i <= s.bookable ORDER BY sp.provider_id) - 1 AS ord
FROM service_providers sp
JOIN users u ON u.user_id = sp.user_id
CROSS JOIN LATERAL (SELECT substring(u.email FROM '^provider([0-9]+)@')::int AS i) x
CROSS JOIN bench_size s
WHERE u.email LIKE 'provider%@bench.sevak.test';
CREATE INDEX ON bench_provider (ord) WHERE bookable;

-- Each provider works in one trade and offers one or both of its services, at their own price.
CREATE TEMP TABLE bench_category AS
SELECT category_id, row_number() OVER (ORDER BY category_id) - 1 AS idx, COUNT(*) OVER () AS n
FROM categories;

CREATE TEMP TABLE bench_service AS
SELECT service_id, category_id, base_price,
       row_number() OVER (PARTITION BY category_id ORDER BY service_id) AS k
FROM services;

INSERT INTO provider_services (provider_id, service_id, price)
SELECT p.provider_id, s.service_id,
       round(s.base_price * (0.85 + ((p.i * 37 + s.service_id * 11) % 35) / 100.0) / 10) * 10
FROM bench_provider p
JOIN bench_category c ON c.idx = p.i % c.n
JOIN bench_service s  ON s.category_id = c.category_id
WHERE s.k = 1 OR p.i % 3 <> 0;

CREATE TEMP TABLE bench_offer AS
SELECT ps.provider_id, ps.service_id, ps.price,
       row_number() OVER (PARTITION BY ps.provider_id ORDER BY ps.service_id) - 1 AS k,
       COUNT(*) OVER (PARTITION BY ps.provider_id)                                AS n
FROM provider_services ps JOIN bench_provider p USING (provider_id);
CREATE INDEX ON bench_offer (provider_id, k);

CREATE TEMP TABLE bench_variant AS
SELECT variant_id, service_id, price, duration,
       row_number() OVER (PARTITION BY service_id ORDER BY variant_id) - 1 AS k,
       COUNT(*) OVER (PARTITION BY service_id)                                AS n
FROM service_variants;

INSERT INTO provider_service_areas (provider_id, area_id)
SELECT p.provider_id, a.area_id FROM bench_provider p JOIN areas a ON a.city_id = p.city_id;

INSERT INTO provider_availability (provider_id, day_of_week, start_time, end_time)
SELECT p.provider_id, d, '09:00', '19:00' FROM bench_provider p CROSS JOIN generate_series(1, 6) d;

INSERT INTO provider_documents (provider_id, document_type, file_url, description, status)
SELECT p.provider_id, 'Aadhaar Card', '/docs/bench/' || p.i || '_aadhaar.pdf', 'Government ID proof',
       CASE WHEN p.bookable THEN 'verified' ELSE 'pending' END
FROM bench_provider p;

-- ── Bookings ────────────────────────────────────────────────
-- Booking g goes to bookable provider (g - 1) % B as that provider's k-th job. Jobs for one provider
-- are three days apart (four when one lands on a Sunday), so they can never overlap: the slot is
-- calculated, not picked at random. Starts are 9:00, 11:00, 14:00 or 16:00, India time.
CREATE TEMP TABLE bench_booking_base AS
SELECT g,
       m.base + g                                          AS booking_id,
       p.provider_id,
       p.city_id,
       d.day + (ARRAY[9, 11, 14, 16])[(1 + (p.ord + k.k) % 4)::int] * interval '1 hour' AS local_start,
       floor(cc.n * power(random(), 1.5))::int             AS city_idx   -- some customers book far more
FROM bench_size s
CROSS JOIN (SELECT MAX(booking_id) AS base FROM bookings) m
CROSS JOIN generate_series(1, s.bookings) g
JOIN bench_provider p ON p.bookable AND p.ord = (g - 1) % s.bookable
CROSS JOIN LATERAL (SELECT (g - 1) / s.bookable AS k) k
CROSS JOIN LATERAL (
    SELECT CASE WHEN EXTRACT(ISODOW FROM x) = 7 THEN x + 1 ELSE x END AS day
    FROM (SELECT (now() AT TIME ZONE 'Asia/Kolkata')::date - 540 + (k.k * 3 + p.ord % 3)::int AS x) y
) d
JOIN bench_city_customers cc ON cc.city_id = p.city_id;

CREATE TEMP TABLE bench_item AS
SELECT b.booking_id, j AS item_no, o.service_id, v.variant_id,
       CASE WHEN j = 1 AND b.g % 11 = 0 THEN 2 ELSE 1 END AS quantity,
       COALESCE(v.price, o.price)                           AS unit_price,
       COALESCE(v.duration, 60)                             AS duration
FROM bench_booking_base b
CROSS JOIN LATERAL generate_series(1, 1 + b.g % 3) j
JOIN bench_offer o ON o.provider_id = b.provider_id AND o.k = (b.g + j) % o.n
LEFT JOIN bench_variant v ON v.service_id = o.service_id AND v.k = (b.g + 3 * j) % v.n;

CREATE TEMP TABLE bench_booking AS
SELECT b.g, b.booking_id, b.provider_id, c.customer_id, h.location_id, c.user_id AS customer_user_id,
       t.subtotal,
       b.local_start AT TIME ZONE 'Asia/Kolkata'                                   AS start_at,
       b.local_start AT TIME ZONE 'Asia/Kolkata' + t.minutes * interval '1 minute' AS end_at,
       CASE
           WHEN b.local_start AT TIME ZONE 'Asia/Kolkata' < now() THEN
               CASE WHEN (b.g * 19) % 100 < 86 THEN 'completed' ELSE 'cancelled' END
           ELSE
               CASE WHEN (b.g * 19) % 100 < 25 THEN 'pending'
                    WHEN (b.g * 19) % 100 < 90 THEN 'confirmed'
                    ELSE 'cancelled' END
       END AS status
FROM bench_booking_base b
JOIN bench_customer c ON c.city_id = b.city_id AND c.city_idx = b.city_idx
JOIN bench_home h     ON h.customer_id = c.customer_id
JOIN (SELECT booking_id, SUM(quantity * unit_price) AS subtotal, SUM(quantity * duration) AS minutes
      FROM bench_item GROUP BY booking_id) t ON t.booking_id = b.booking_id;

-- Pending requests are recent, so the expiry job would not cancel them; others were made 1-9 days ahead.
ALTER TABLE bench_booking ADD COLUMN created_at TIMESTAMPTZ;
UPDATE bench_booking SET created_at = CASE
    WHEN status = 'pending' THEN now() - interval '1 minute' * (5 + g % 1200)
    ELSE LEAST(now() - interval '1 hour', start_at - interval '1 hour' * (24 + g % 200))
END;

-- The triggers would log and check each row one at a time; their work is done in bulk below.
ALTER TABLE bookings DISABLE TRIGGER USER;
ALTER TABLE booking_items DISABLE TRIGGER USER;
ALTER TABLE provider_reviews DISABLE TRIGGER USER;
ALTER TABLE refunds DISABLE TRIGGER USER;

INSERT INTO bookings (booking_id, customer_id, provider_id, location_id, coupon_id, scheduled_start,
                      scheduled_end, status, subtotal, discount, total_amount, special_instructions,
                      created_at, updated_at)
SELECT booking_id, customer_id, provider_id, location_id, NULL, start_at, end_at, status, subtotal, 0,
       subtotal, CASE WHEN g % 9 = 0 THEN 'Please call before arriving' END, created_at, created_at
FROM bench_booking
ORDER BY booking_id;

SELECT setval(pg_get_serial_sequence('bookings', 'booking_id'), (SELECT MAX(booking_id) FROM bookings));

INSERT INTO booking_items (booking_id, item_no, service_id, variant_id, quantity, unit_price)
SELECT booking_id, item_no, service_id, variant_id, quantity, unit_price
FROM bench_item
ORDER BY booking_id, item_no;

-- ── Status history, as the trigger would have written it ────
-- Half the cancelled jobs had been accepted first.
INSERT INTO booking_status_log (booking_id, old_status, new_status, changed_at, remarks)
SELECT booking_id, old_status, new_status, changed_at, remarks
FROM (
    SELECT booking_id, 1 AS step, NULL AS old_status, 'pending' AS new_status, created_at AS changed_at,
           'Booking placed by customer' AS remarks
    FROM bench_booking
    UNION ALL
    SELECT booking_id, 2, 'pending', 'confirmed', created_at + interval '20 minutes', 'Provider accepted'
    FROM bench_booking
    WHERE status IN ('confirmed', 'completed') OR (status = 'cancelled' AND g % 2 = 0)
    UNION ALL
    SELECT booking_id, 3, 'confirmed', 'in_progress', start_at, 'Provider arrived'
    FROM bench_booking WHERE status = 'completed'
    UNION ALL
    SELECT booking_id, 4, 'in_progress', 'completed', end_at, 'Service finished'
    FROM bench_booking WHERE status = 'completed'
    UNION ALL
    SELECT booking_id, 3, CASE WHEN g % 2 = 0 THEN 'confirmed' ELSE 'pending' END, 'cancelled',
           CASE WHEN start_at < now() THEN start_at - interval '3 hours' ELSE created_at + interval '30 minutes' END,
           'Customer changed plans'
    FROM bench_booking WHERE status = 'cancelled'
) steps
ORDER BY booking_id, step;

INSERT INTO cancellations (booking_id, reason, cancelled_at, cancelled_by)
SELECT booking_id, 'Customer changed plans',
       CASE WHEN start_at < now() THEN start_at - interval '3 hours' ELSE created_at + interval '30 minutes' END,
       customer_user_id
FROM bench_booking WHERE status = 'cancelled';

-- ── Payments ────────────────────────────────────────────────
-- One in twenty finished jobs had a failed attempt first; 3% were never paid.
INSERT INTO payments (booking_id, payment_method, status, amount, gateway_ref, paid_at, created_at, failure_reason)
SELECT booking_id, 'Credit Card', 'failed', subtotal, NULL, NULL, end_at + interval '5 minutes',
       'Card declined by the bank'
FROM bench_booking WHERE status = 'completed' AND g % 20 = 0;

INSERT INTO payments (booking_id, payment_method, status, amount, gateway_ref, paid_at, created_at)
SELECT booking_id,
       CASE WHEN g % 20 < 11 THEN 'UPI' WHEN g % 20 < 15 THEN 'Credit Card'
            WHEN g % 20 < 18 THEN 'Debit Card' ELSE 'Net Banking' END,
       'success', subtotal, 'BENCH_' || booking_id, paid, paid
FROM (
    SELECT *, CASE WHEN status = 'completed' THEN end_at + interval '15 minutes'
                   ELSE created_at + interval '25 minutes' END AS paid
    FROM bench_booking
    WHERE (status = 'completed' AND g % 33 <> 0) OR (status = 'confirmed' AND g % 3 = 0)
) paid_jobs;

-- A third of the cancelled jobs that had been accepted were paid, then refunded.
CREATE TEMP TABLE bench_refunded AS
SELECT booking_id, subtotal, created_at,
       CASE WHEN start_at < now() THEN start_at - interval '3 hours' ELSE created_at + interval '30 minutes' END
           AS cancelled_at
FROM bench_booking WHERE status = 'cancelled' AND g % 2 = 0 AND g % 3 = 0;

INSERT INTO payments (booking_id, payment_method, status, amount, gateway_ref, paid_at, created_at)
SELECT booking_id, 'UPI', 'refunded', subtotal, 'BENCH_' || booking_id,
       created_at + interval '25 minutes', created_at + interval '25 minutes'
FROM bench_refunded;

INSERT INTO refunds (payment_id, amount, status, requested_at, processed_at)
SELECT p.payment_id, r.subtotal,
       CASE WHEN r.cancelled_at + interval '2 days' < now() THEN 'processed' ELSE 'pending' END,
       r.cancelled_at,
       CASE WHEN r.cancelled_at + interval '2 days' < now() THEN r.cancelled_at + interval '2 days' END
FROM bench_refunded r JOIN payments p ON p.booking_id = r.booking_id AND p.status = 'refunded';

-- ── Reviews: about two thirds of finished jobs ──────────────
INSERT INTO provider_reviews (booking_id, rating, comment, created_at, updated_at)
SELECT booking_id,
       CASE WHEN (g * 13) % 100 < 45 THEN 5 WHEN (g * 13) % 100 < 80 THEN 4
            WHEN (g * 13) % 100 < 92 THEN 3 WHEN (g * 13) % 100 < 97 THEN 2 ELSE 1 END,
       (ARRAY['On time and tidy.', 'Fixed it quickly.', 'Polite and careful.', 'Good work, fair price.',
              'Arrived a little late.', 'Would book again.', NULL, NULL])[1 + g % 8],
       LEAST(end_at + interval '20 hours', now()),
       LEAST(end_at + interval '20 hours', now())
FROM bench_booking
WHERE status = 'completed' AND (g * 31) % 100 < 65;

-- ── Complaints: about one finished job in a hundred ─────────
INSERT INTO complaints (booking_id, subject, description, status, raised_by, assigned_admin_id, created_at,
                        resolved_at, resolution_notes)
SELECT booking_id,
       (ARRAY['Arrived late', 'Job not finished', 'Charged more than quoted', 'Damage to property'])[1 + g % 4],
       'Raised by the customer after the visit.',
       (ARRAY['open', 'in_progress', 'resolved', 'closed'])[1 + (g / 100) % 4],
       customer_user_id,
       CASE WHEN (g / 100) % 4 = 0 THEN NULL ELSE 1 + g % 2 END,
       LEAST(end_at + interval '2 days', now()),
       CASE WHEN (g / 100) % 4 >= 2 THEN LEAST(end_at + interval '4 days', now()) END,
       CASE WHEN (g / 100) % 4 >= 2 THEN 'Spoke to both sides and settled it.' END
FROM bench_booking
WHERE status = 'completed' AND g % 100 = 7;

-- ── Cached ratings, computed once instead of once per review ─
UPDATE service_providers sp
SET avg_rating = r.avg_rating, review_count = r.review_count
FROM (
    SELECT b.provider_id, ROUND(AVG(pr.rating), 2) AS avg_rating, COUNT(*) AS review_count
    FROM provider_reviews pr JOIN bookings b ON b.booking_id = pr.booking_id
    GROUP BY b.provider_id
) r
WHERE sp.provider_id = r.provider_id;

ALTER TABLE bookings ENABLE TRIGGER USER;
ALTER TABLE booking_items ENABLE TRIGGER USER;
ALTER TABLE provider_reviews ENABLE TRIGGER USER;
ALTER TABLE refunds ENABLE TRIGGER USER;

REFRESH MATERIALIZED VIEW mv_provider_leaderboard;
REFRESH MATERIALIZED VIEW mv_city_revenue_summary;
REFRESH MATERIALIZED VIEW mv_category_revenue;

-- A database that has been running for a while is vacuumed and has fresh statistics.
VACUUM (ANALYZE);
