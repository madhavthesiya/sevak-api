# SEVAK benchmarks

Every performance claim in this project, measured on about **1 million bookings**. The raw results are in [`benchmarks/results.json`](benchmarks/results.json), and every query plan is saved in [`benchmarks/plans/`](benchmarks/plans/).

## Headline results

| What | Before | After | How |
|---|---:|---:|---|
| Home page "highest rated" | 2,334 ms | 8.0 ms | Read the stored leaderboard instead of computing it on every visit |
| Admin bookings list, page 5,000 | 514 ms | 2.4 ms | An index on the keyset's sort columns (migration V13) |
| Live leaderboard | 3,041 ms | 452 ms | Count each provider's jobs first, then join, instead of `COUNT(DISTINCT)` |
| Search | 39.0 ms | 2.4 ms | Rank by the stored score instead of averaging 441,461 reviews per search |
| Admin revenue chart | 4,682 ms | 0.019 ms | Store the result, refreshed every 15 minutes |

Every rewrite returned **exactly the same rows** as the query it replaced on this dataset, or the benchmark would have failed. Search returned the same 8,010 results in the same order.

## Setup

| | |
|---|---|
| Machine | Windows 11 laptop, Intel Core Ultra 5 125H, 16 GB RAM (15.4 GB usable), plugged in, other apps closed |
| Docker | Docker Desktop (WSL 2), 18 CPUs and 8 GB available to containers |
| Database | PostgreSQL 16.13 (`postgres:16-alpine`). `shared_buffers` 256 MB; everything else default (`work_mem` 4 MB, 2 parallel workers per query) |
| Database size | 1,593 MB |
| Load time | 417 seconds |

| Table | Rows |
|---|---:|
| users | 55,015 |
| customers | 50,007 |
| service_providers | 5,006 |
| locations | 60,010 |
| bookings | 1,000,010 |
| booking_items | 2,000,015 |
| booking_status_log | 3,522,573 |
| payments | 817,085 |
| provider_reviews | 441,461 |
| complaints | 8,659 |

### The dataset

[`api/sql/benchmarks/generate_large_dataset.sql`](../api/sql/benchmarks/generate_large_dataset.sql) builds it with `generate_series`, and is reproducible because `random()` is seeded:

- Demand is uneven: 28% of customers and providers are in Mumbai, 12% in Pune. Some customers book far more often than others.
- Each provider works in one trade. Bookings start at 9:00, 11:00, 14:00 or 16:00 India time, from about 18 months ago to about 3 months ahead.
- Status follows time: past bookings are 86% completed and 14% cancelled; future ones are pending or confirmed. Payments, refunds, reviews (about two thirds of finished jobs) and complaints follow from that.
- **Every constraint stays on**, including foreign keys, CHECKs and the double-booking exclusion constraint, so PostgreSQL rejects any row the generator gets wrong. A provider's jobs are three days apart, so they can't overlap: the slot is calculated, not picked at random.
- Only the row-by-row triggers are paused during the load. What they would have written (the status history and cached ratings) is written in bulk instead. Afterwards, the project's own [integrity checks](../api/sql/checks/data_integrity.sql) must all return zero rows.

## Method

- The benchmark ([`QueryBenchmark`](../api/src/test/java/com/sevak/api/benchmark/QueryBenchmark.java)) measures **the SQL the app actually runs**. It reads the same constants as the repositories, so the measured query can't drift from the shipped one.
- Each query runs once to warm the cache, then three times under `EXPLAIN (ANALYZE, BUFFERS)`. The **median execution time** is reported. That is time inside PostgreSQL, without the network or JSON.
- A "before" variant changes the schema, for example by dropping an index, **inside a transaction that is rolled back**. So before and after run against exactly the same data.
- After loading, the database writes its dirty pages (`CHECKPOINT`), so a background checkpoint doesn't slow the first measurements. Before I added this, the same query varied by more than 3x between runs.
- Where a query was rewritten, the old version is kept in [`OldQueries`](../api/src/test/java/com/sevak/api/benchmark/OldQueries.java), copied from git. The run fails unless the old and new versions return identical rows.
- The published numbers come from one run on a fresh database with the machine otherwise idle. Two earlier runs with other apps open gave the same conclusions, except for one rewrite, which is described below.

Run it yourself (it needs Docker and takes about 15 minutes):

```bash
cd api
./mvnw test -Dtest=QueryBenchmark -Dsurefire.failIfNoSpecifiedTests=false
```

## Experiments

All times are medians in milliseconds.

### 1. Search: the trigram index didn't help; the real cost was elsewhere

| Variant | ms |
|---|---:|
| Old query, without the `pg_trgm` index | 31.7 |
| Old query, with it | 39.0 |
| Ranked by the stored leaderboard score | **2.4** |

The trigram index is on `services`, which has 12 rows. PostgreSQL reads 12 rows faster than it reads an index, so it never used it. The plan showed that 35 of the 39 ms went to one line:

```
->  Parallel Seq Scan on provider_reviews  (actual time=0.005..11.315 rows=147154 loops=3)
```

To rank providers fairly, search used a Bayesian score that needs the platform-wide average rating, and it recomputed that from all 441,461 reviews on every search. The leaderboard's materialized view already stores the same score, so search now joins to it by primary key. It returns the same results in the same order, because it ranks by the **unrounded** score the old query computed. The trade-off is freshness: a new review changes the ranking at the next refresh, within 15 minutes.

The trigram index stays. It costs almost nothing on a 12-row table, and it would matter if the catalogue grew to thousands of services.

### 2. My Bookings: the composite index matters for busy customers

| Variant | Median customer | Busiest customer |
|---|---:|---:|
| No index on `customer_id` | 42.2 | |
| Single column `(customer_id)`, as in V1 | 0.38 | 1.84 |
| Composite `(customer_id, scheduled_start DESC)`, as in V11 | 0.40 | **0.33** |

Any index removes the 42 ms full scan. For the median customer, the two indexes are the same. For the busiest customer, the single-column index has to read and sort all their bookings to find the newest 20, while the composite index reads them already in order and stops after 20: 5.6x faster.

### 3. Pending jobs: the partial index adds nothing measurable

| Variant | ms |
|---|---:|
| Neither provider index | 0.51 |
| Composite `(provider_id, status, scheduled_start)` only | 0.34 |
| Partial index `WHERE status = 'pending'` only | 0.33 |
| Both, as shipped | 0.32 |

Even with both indexes dropped, the query took half a millisecond, because the double-booking exclusion constraint has its own GiST index, which starts with `provider_id` and covers open bookings:

```
->  Bitmap Index Scan on ex_provider_no_double_booking  (actual time=0.010..0.010 rows=25 loops=1)
```

The partial index is small (1.3 MB, against 44 MB for the composite), so I kept it. But I don't claim it makes anything faster.

### 4. Leaderboard: the rewrite and the stored copy

| Variant | ms |
|---|---:|
| Old live view, `COUNT(DISTINCT)` | 3,041 |
| New live view, jobs counted first | **452** |
| Materialized view | 20.1 |

The old view joined providers to every completed booking and payment, then counted distinct bookings. Counting distinct values means sorting, and the sort didn't fit in memory:

```
Sort Method: external merge  Disk: 16680kB
```

A booking has at most one successful payment (a unique partial index guarantees it), so the join can't duplicate a booking. That means `COUNT(*)` gives the same answer as `COUNT(DISTINCT)`. The new view counts each provider's completed jobs in one pass first, then joins those totals to the providers. Refreshing the stored copy dropped too, from 4.0 to 5.3 s in the first full run to 437 ms.

### 5. Admin bookings list: keyset pagination needs an index on its sort key

| Variant | Page 1 | Page 5,000 |
|---|---:|---:|
| OFFSET, no index | 654 | 881 |
| Keyset, no index | | 514 |
| OFFSET, with the new index | 3.2 | 492 |
| Keyset, with the new index | | **2.4** |

The admin list already used keyset pagination, but nothing indexed `scheduled_start` after migration V6 dropped the old date column. So every page, including page 1, sorted the whole table. The new index (V13) makes a keyset page read 20 rows from the index:

```
Keyset, page 5,000, no index:    Buffers: shared hit=6301 read=11367   Execution Time: 513.8 ms
Keyset, page 5,000, with index:  Buffers: shared hit=246               Execution Time: 2.4 ms
```

With the index, OFFSET is fast for page 1 but still reads and discards 99,980 rows to reach page 5,000. That is why the endpoint uses a keyset.

### 6. Revenue by category: correctness, a rewrite that failed, and a stored summary

| Variant | ms |
|---|---:|
| Before task 0.4: joins payments to items, so payments count more than once | 2,615 |
| Task 0.4: a window function splits each payment across categories (correct) | 4,682 |
| Phase 6 attempt: divide by the booking's subtotal instead (correct, **rejected**) | 8,418 |
| Phase 6: read the stored summary | **0.019** |

**The bug the old query had, now measured.** On this dataset the old query reported **₹260.87 crore** of revenue. The real total of successful payments is **₹113.03 crore**, so it over-counted 2.3 times. The correct query returns exactly ₹113.03 crore.

**The rewrite that looked faster and wasn't.** A trigger guarantees that a booking's `subtotal` equals its items, so I divided by `subtotal` instead of re-adding items with a window function. It returned identical rows, and two runs with other apps open showed it faster (5.5 s vs 6.0 s, and 8.7 s vs 15.9 s). The clean run showed it 1.8x **slower**. The plan explains why. PostgreSQL estimated the item aggregation would produce 55,212 rows; it produced 751,374. On that wrong estimate it chose a parallel plan in which all three processes built the same hash table separately (`loops=3`), and the hash spilled to disk. I reverted it, and it stays in the benchmark as a recorded failure.

**The fix that worked.** The live query took 4.7 s here, and between 5.8 and 15.9 s in the noisier runs, against the app's 5-second query limit. The chart would have failed intermittently. It now reads `mv_category_revenue`, rebuilt every 15 minutes with the other summaries, like revenue by city already was. A test checks that the stored copy equals the live calculation.

### 7. Free time slots: the exclusion constraint is for correctness, not speed

| Variant | ms |
|---|---:|
| Without the exclusion constraint's GiST index | 3.8 |
| With it | 3.7 |

The slots query checks for overlapping bookings. Without the GiST index, PostgreSQL uses the `(provider_id, status, scheduled_start)` index instead, which is just as fast for one provider's 28 open bookings. The exclusion constraint's job is to make double booking impossible, even when two requests race, which [BookingConcurrencyTest](../api/src/test/java/com/sevak/api/BookingConcurrencyTest.java) covers.

### 8. Home page: from 2.3 seconds to 8 milliseconds

| Variant | ms |
|---|---:|
| Computed live on every visit | 2,334 |
| Read from the stored leaderboard | **8.0** |

The home page's "highest rated" list ran the full live leaderboard for every visitor. It now reads the stored copy, which returns the same rows.

### What freshness costs

The stored summaries are rebuilt every 15 minutes in the background, under an advisory lock so two app instances don't do it at once:

| Summary | Refresh, median ms |
|---|---:|
| Provider leaderboard | 437 |
| Revenue by city | 2,268 |
| Revenue by category | not measured separately; it runs the 4,682 ms query from experiment 6 |

These run outside any user request. Measuring them exposed a bug: the refresh went through the app's database client, which cancels any statement after 5 seconds. At this size the category refresh needed more, so it was cancelled, and the summaries would never have refreshed. When the refresh also ran at startup, the cancellation stopped the whole app. Background refreshes now have their own 5-minute limit, and a failed startup refresh logs a warning instead of stopping the app.

## Every query in the app

The "Show the SQL" drawer shows these numbers next to each query. They come from [`query-timings.json`](../api/src/main/resources/benchmarks/query-timings.json), which the benchmark writes. Values are for a typical customer and provider.

| Query | ms |
|---|---:|
| Service catalogue | 0.066 |
| Provider search | 2.7 |
| Highest rated providers | 12.8 |
| Provider profile | 1.2 |
| Provider reviews | 2.1 |
| Free time slots | 3.5 |
| Live price | 0.056 |
| My bookings | 0.58 |
| Booking detail | 0.31 |
| Saved addresses | 0.13 |
| Job inbox | 0.50 |
| Earnings by method | 1.1 |
| Earnings by month | 1.1 |
| Services offered | 0.068 |
| Revenue by category (stored) | 0.017 |
| Revenue by city (stored) | 0.016 |
| Leaderboard, live | 448 |
| Leaderboard, stored | 14.8 |
| Complaints board | 154 |
| Verification queue | 1.5 |

## Load test

[k6](../api/load-test/booking-journey.js): 50 virtual users for 2 minutes, each logged in as a different generated customer. Each iteration searches, opens a provider's profile and checks their free times. One in five then books the first free time. There is no pause between iterations, so 50 users here send far more traffic than 50 people would.

| Connection pool | Requests/s | p50 | p95 | p99 | Errors |
|---|---:|---:|---:|---:|---:|
| **10 (default, kept)** | **182** | 212 ms | 548 ms | 1.13 s | 0.08% |
| 20 | 158 | 268 ms | 534 ms | 1.24 s | 0.05% |

**Choosing the pool size.** HikariCP's own metrics showed where the time went. With 10 connections, a request waited **209 ms** on average to get a connection, then held it for 54 ms. So the pool was the queue, not the queries, which take a few milliseconds. Doubling the pool didn't help: each connection was then held for 125 ms instead of 54, and throughput fell to 158 requests a second. k6, the API and PostgreSQL were all running on one laptop, so more connections only meant more queries competing for the same CPUs. I kept the default of 10. On a real server, with the database on its own machine, I'd measure again.

**Bookings under load.** In the pool-10 run, 1,115 bookings were created, and 216 attempts were correctly refused with `409 slot-taken` because another user had just booked the same time.

**Errors.** 19 of 23,186 requests failed. Each user logs at most three failures, and all 13 that were logged were `422 This provider does not work in that area`. That's correct behaviour caused by the dataset: the 6 demo providers only cover the original 10 areas, and the generated customers live in the newer ones. It points to a real product gap, though: search filters by city, so it can list providers who don't serve the customer's area.

**What missed its target.** Search's p95 under load was 594 ms, against a 500 ms target. Its query takes 2.4 ms, so the time is spent waiting for a connection and the CPU on a shared laptop, not in the database.

Raw output: [`load-test-output-pool10.txt`](benchmarks/load-test-output-pool10.txt), [`load-test-output-pool20.txt`](benchmarks/load-test-output-pool20.txt).

## What I learned

- **Measure before indexing.** Two of the indexes I expected to help (trigram on search, partial on pending jobs) made no measurable difference. The real costs were a hidden full scan in search and a missing index nobody had noticed.
- **A small demo hides big problems.** Three pages were fine on 15 demo bookings and slow or failing on a million: the home page (2.3 s), the admin revenue chart (near the 5 s limit) and the admin bookings list (0.5 s a page).
- **A noisy measurement can mislead.** The revenue rewrite looked faster in two noisy runs and was slower in a clean one. Without the clean run I would have shipped a slower query.
- **Faster must also mean the same.** Every rewrite is checked row by row against the query it replaced.
- **Load testing finds what query benchmarks can't.** The refresh timeout bug only appeared when the app ran against the big database, and the first load-test attempt silently tested a stopped server, which is why the script now checks the API answers before starting.

## Limits

- One laptop runs everything. Absolute numbers will differ on a server; the comparisons between variants are the point.
- Warm cache. Each query runs once before it is measured, so these are steady-state numbers, not first-request numbers.
- EXPLAIN ANALYZE adds a little overhead of its own, the same for both sides of each comparison.
- The data is generated. It is shaped like real use, but real data would have its own skew.
