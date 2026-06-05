# SEVAK — Query Optimization Report

## Overview

This document demonstrates how database-level optimizations in SEVAK reduce query execution
time from expensive multi-table JOINs to pre-computed O(1) reads using **Materialized Views**.

---

## The Problem: Expensive Leaderboard Query

The provider leaderboard endpoint (`GET /api/admin/leaderboard`) runs the following query
on every request:

```sql
SELECT
    RANK() OVER (ORDER BY sp.avg_rating DESC NULLS LAST,
                 COUNT(b.booking_id) DESC)   AS rank,
    sp.provider_id, u.email, ci.city_name, sp.avg_rating,
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
```

### Complexity Analysis

| Operation | Cost |
|:----------|:-----|
| Tables joined | 4 (`service_providers`, `users`, `cities`, `bookings`) |
| Aggregation | `COUNT`, `SUM`, `COALESCE` per provider |
| Window function | `RANK() OVER (...)` — requires full sort after aggregation |
| Sort | `ORDER BY rank` — final ordering pass |

This query performs a **sequential scan** across all bookings, groups by provider, computes
aggregates, applies the window function, and then sorts. Every single API call repeats
this entire computation.

---

## The Solution: Materialized View

A Materialized View pre-computes and **physically stores** the query result on disk.
Subsequent reads are a simple table scan — no JOINs, no aggregation, no window functions.

```sql
CREATE MATERIALIZED VIEW mv_provider_leaderboard AS
SELECT
    RANK() OVER (...) AS rank,
    sp.provider_id, u.email, ci.city_name, sp.avg_rating,
    COUNT(b.booking_id) AS jobs_completed,
    COALESCE(SUM(b.total_amount), 0) AS total_revenue
FROM service_providers sp
JOIN users u    ON sp.user_id = u.user_id
JOIN cities ci  ON sp.city_id = ci.city_id
LEFT JOIN bookings b ON sp.provider_id = b.provider_id AND b.status = 'completed'
WHERE sp.is_active = TRUE
GROUP BY sp.provider_id, u.email, ci.city_name, sp.avg_rating
ORDER BY rank;

-- Unique index enables REFRESH CONCURRENTLY (no read lock during refresh)
CREATE UNIQUE INDEX idx_mv_leaderboard_provider ON mv_provider_leaderboard(provider_id);
```

### API Usage

```
GET /api/admin/leaderboard           → Runs live 4-table JOIN (real-time, slower)
GET /api/admin/leaderboard?fast=true → Reads from materialized view (pre-computed, O(1))
POST /api/admin/refresh-views        → Rebuilds materialized views with latest data
```

---

## Performance Comparison

| Metric | Live Query (V1) | Materialized View (V2) |
|:-------|:----------------|:----------------------|
| Tables scanned | 4 | 1 (pre-computed) |
| JOINs executed | 3 (INNER + LEFT) | 0 |
| Aggregation | Per-request | Pre-computed |
| Window function | Per-request | Pre-computed |
| Time complexity | O(n × m) where n=providers, m=bookings | O(k) where k=active providers |
| Trade-off | Always real-time | Stale until refreshed |

### When to Use Each

- **Live query** (`?fast=false`): When you need guaranteed up-to-the-second accuracy
  (e.g., right after confirming a new booking).
- **Materialized view** (`?fast=true`): For dashboard displays, admin panels, and any
  read-heavy endpoint where millisecond-stale data is acceptable.
- **Refresh strategy**: Call `POST /api/admin/refresh-views` after batch operations,
  or schedule it via cron (e.g., every 15 minutes in production).

---

## City Revenue Summary (Second Materialized View)

The same optimization is applied to city-level revenue analytics:

```sql
CREATE MATERIALIZED VIEW mv_city_revenue_summary AS
SELECT
    ci.city_name,
    COUNT(DISTINCT b.booking_id)   AS total_bookings,
    COUNT(DISTINCT b.customer_id)  AS unique_customers,
    COALESCE(SUM(p.amount), 0)     AS total_revenue,
    ROUND(COALESCE(AVG(p.amount), 0), 2) AS avg_booking_value
FROM cities ci
JOIN areas ar       ON ci.city_id      = ar.city_id
JOIN locations loc  ON ar.area_id      = loc.area_id
JOIN bookings b     ON loc.location_id = b.location_id
LEFT JOIN payments p ON b.booking_id   = p.booking_id AND p.status = 'success'
GROUP BY ci.city_name
ORDER BY total_revenue DESC;
```

This replaces a **5-table JOIN** (cities → areas → locations → bookings → payments)
with a single pre-computed read.

---

## Indexes

The following indexes support both the live queries and the base tables that feed
the materialized views:

| Index | Table | Purpose |
|:------|:------|:--------|
| `idx_bookings_provider` | `bookings` | Speed up LEFT JOIN on provider_id |
| `idx_bookings_customer` | `bookings` | Speed up customer booking lookups |
| `idx_bookings_location` | `bookings` | Speed up location-based revenue joins |
| `idx_payments_booking` | `payments` | Speed up payment lookups by booking |
| `idx_prov_reviews_booking` | `provider_reviews` | Speed up review aggregation |
| `idx_mv_leaderboard_provider` | `mv_provider_leaderboard` | Required for REFRESH CONCURRENTLY |
| `idx_mv_city_revenue` | `mv_city_revenue_summary` | Required for REFRESH CONCURRENTLY |

---

## Key Takeaway

> Materialized Views shift computation from **read time** to **write time**.
> Instead of running an expensive query 1,000 times per day, you run it once
> and serve the cached result. This is the same pattern used by analytics
> dashboards at companies like Uber, Airbnb, and LinkedIn.
