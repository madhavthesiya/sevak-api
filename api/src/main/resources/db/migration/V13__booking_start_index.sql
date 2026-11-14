-- Task 6.3: the admin bookings list pages with a keyset on (scheduled_start, booking_id), newest first.
-- Nothing indexed scheduled_start after V6 dropped scheduled_date, so every page sorted the whole table:
-- 1.26 s a page at 1M bookings. With this index a page reads only its own 20 rows (docs/BENCHMARKS.md).
--
-- On a large live table, build it with CREATE INDEX CONCURRENTLY outside a transaction instead, so writes
-- are not blocked while it builds. The production table is small enough today for a plain build.
CREATE INDEX idx_bookings_start_id ON bookings (scheduled_start DESC, booking_id DESC);
