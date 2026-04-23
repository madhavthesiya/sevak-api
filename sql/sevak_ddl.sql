-- ============================================================
--  SEVAK – Service Marketplace Database Management System
--  DDL Script  |  IT214 DBMS Project
--  Schema: sevak
-- ============================================================

-- Drop and recreate schema for clean slate
DROP SCHEMA IF EXISTS sevak CASCADE;
CREATE SCHEMA sevak;
SET search_path = sevak;

-- ============================================================
-- 1. USERS
-- ============================================================
CREATE TABLE users (
    user_id    SERIAL       PRIMARY KEY,
    email      VARCHAR(255) NOT NULL UNIQUE,
    password   VARCHAR(255) NOT NULL,
    role       VARCHAR(20)  NOT NULL CHECK (role IN ('customer','provider','admin')),
    status     VARCHAR(20)  NOT NULL DEFAULT 'active'
                            CHECK (status IN ('active','inactive','banned')),
    created_at TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- ============================================================
-- 2. CUSTOMERS
-- ============================================================
CREATE TABLE customers (
    customer_id SERIAL       PRIMARY KEY,
    user_id     INT          NOT NULL UNIQUE REFERENCES users(user_id) ON DELETE CASCADE,
    name        VARCHAR(100) NOT NULL,
    phone       VARCHAR(20)
);

-- ============================================================
-- 3. CITIES
-- ============================================================
CREATE TABLE cities (
    city_id   SERIAL       PRIMARY KEY,
    city_name VARCHAR(100) NOT NULL,
    state     VARCHAR(100) NOT NULL
);

-- ============================================================
-- 4. SERVICE PROVIDERS
-- ============================================================
CREATE TABLE service_providers (
    provider_id         SERIAL         PRIMARY KEY,
    user_id             INT            NOT NULL UNIQUE REFERENCES users(user_id) ON DELETE CASCADE,
    city_id             INT            NOT NULL REFERENCES cities(city_id),
    bio                 TEXT,
    experience_years    INT            CHECK (experience_years >= 0),
    avg_rating          NUMERIC(3,2)   CHECK (avg_rating BETWEEN 0 AND 5),
    verification_status VARCHAR(20)    NOT NULL DEFAULT 'pending'
                                       CHECK (verification_status IN ('pending','verified','rejected')),
    custom_price        NUMERIC(10,2),
    is_active           BOOLEAN        NOT NULL DEFAULT TRUE
);

-- ============================================================
-- 5. ADMINS
-- ============================================================
CREATE TABLE admins (
    admin_id    SERIAL       PRIMARY KEY,
    user_id     INT          NOT NULL UNIQUE REFERENCES users(user_id) ON DELETE CASCADE,
    admin_role  VARCHAR(50)  NOT NULL,
    permissions TEXT,
    department  VARCHAR(100)
);

-- ============================================================
-- 6. AREAS
-- ============================================================
CREATE TABLE areas (
    area_id   SERIAL       PRIMARY KEY,
    city_id   INT          NOT NULL REFERENCES cities(city_id),
    area_name VARCHAR(100) NOT NULL,
    pincode   VARCHAR(10)  NOT NULL
);

-- ============================================================
-- 7. LOCATIONS
-- (Physical location data — BCNF: {lat, lng} → {area, street, landmark})
-- ============================================================
CREATE TABLE locations (
    location_id SERIAL PRIMARY KEY,
    area_id     INT NOT NULL REFERENCES areas(area_id),
    street      VARCHAR(255) NOT NULL,
    landmark    VARCHAR(255),
    latitude    NUMERIC(10,7),
    longitude   NUMERIC(10,7),
    UNIQUE (latitude, longitude)
);

-- ============================================================
-- 8. CUSTOMER ADDRESSES
-- (Maps customers to locations with a label — BCNF compliant)
-- ============================================================
CREATE TABLE customer_addresses (
    customer_id INT NOT NULL REFERENCES customers(customer_id) ON DELETE CASCADE,
    location_id INT NOT NULL REFERENCES locations(location_id) ON DELETE CASCADE,
    label       VARCHAR(50),
    PRIMARY KEY (customer_id, location_id)
);

-- ============================================================
-- 9. CATEGORIES
-- ============================================================
CREATE TABLE categories (
    category_id   SERIAL       PRIMARY KEY,
    category_name VARCHAR(100) NOT NULL UNIQUE,
    description   TEXT
);

-- ============================================================
-- 9. SERVICES
-- ============================================================
CREATE TABLE services (
    service_id   SERIAL         PRIMARY KEY,
    category_id  INT            NOT NULL REFERENCES categories(category_id),
    service_name VARCHAR(150)   NOT NULL,
    base_price   NUMERIC(10,2)  NOT NULL CHECK (base_price >= 0),
    description  TEXT
);

-- ============================================================
-- 10. SERVICE VARIANTS  (Weak entity of Service)
-- ============================================================
CREATE TABLE service_variants (
    variant_id   SERIAL         PRIMARY KEY,
    service_id   INT            NOT NULL REFERENCES services(service_id) ON DELETE CASCADE,
    variant_name VARCHAR(100)   NOT NULL,
    price        NUMERIC(10,2)  NOT NULL CHECK (price >= 0),
    duration     INT            CHECK (duration > 0),   -- minutes
    UNIQUE (service_id, variant_name)
);

-- ============================================================
-- 11. PROVIDER SERVICES (M:N Offers Table - FIXED)
-- ============================================================
CREATE TABLE provider_services (
    provider_id INT NOT NULL REFERENCES service_providers(provider_id) ON DELETE CASCADE,
    service_id  INT NOT NULL REFERENCES services(service_id) ON DELETE CASCADE,
    PRIMARY KEY (provider_id, service_id)
);

-- ============================================================
-- 12. COUPONS
-- ============================================================
CREATE TABLE coupons (
    coupon_id      SERIAL        PRIMARY KEY,
    code           VARCHAR(30)   NOT NULL UNIQUE,
    discount_type  VARCHAR(20)   NOT NULL CHECK (discount_type IN ('percentage','flat')),
    discount_value NUMERIC(10,2) NOT NULL CHECK (discount_value > 0),
    min_order      NUMERIC(10,2) DEFAULT 0,
    usage_limit    INT,
    valid_from     DATE          NOT NULL,
    valid_to       DATE          NOT NULL,
    CHECK (valid_to >= valid_from)
);

-- ============================================================
-- 13. BOOKINGS
-- ============================================================
CREATE TABLE bookings (
    booking_id            SERIAL        PRIMARY KEY,
    customer_id           INT           NOT NULL REFERENCES customers(customer_id),
    provider_id           INT           NOT NULL REFERENCES service_providers(provider_id),
    location_id           INT           NOT NULL REFERENCES locations(location_id),
    coupon_id             INT           REFERENCES coupons(coupon_id),
    scheduled_date        DATE          NOT NULL,
    scheduled_time        TIME          NOT NULL,
    status                VARCHAR(30)   NOT NULL DEFAULT 'pending'
                                        CHECK (status IN ('pending','confirmed','in_progress',
                                                          'completed','cancelled')),
    total_amount          NUMERIC(10,2) NOT NULL CHECK (total_amount >= 0),
    special_instructions  TEXT
);

-- ============================================================
-- 14. BOOKING ITEMS  (Weak entity of Booking)
-- ============================================================
CREATE TABLE booking_items (
    booking_id INT           NOT NULL REFERENCES bookings(booking_id) ON DELETE CASCADE,
    item_no    INT           NOT NULL,
    service_id INT           NOT NULL REFERENCES services(service_id),
    variant_id INT           REFERENCES service_variants(variant_id),
    quantity   INT           NOT NULL DEFAULT 1 CHECK (quantity > 0),
    unit_price NUMERIC(10,2) NOT NULL CHECK (unit_price >= 0),
    PRIMARY KEY (booking_id, item_no)
);

-- ============================================================
-- 15. BOOKING STATUS LOG  (Weak entity of Booking)
-- ============================================================
CREATE TABLE booking_status_log (
    booking_id INT          NOT NULL REFERENCES bookings(booking_id) ON DELETE CASCADE,
    log_time   TIMESTAMP    NOT NULL DEFAULT NOW(),
    status     VARCHAR(30)  NOT NULL,
    remarks    TEXT,
    PRIMARY KEY (booking_id, log_time)
);

-- ============================================================
-- 16. PAYMENTS  (1:1 with Booking)
-- ============================================================
CREATE TABLE payments (
    payment_id     SERIAL        PRIMARY KEY,
    booking_id     INT           NOT NULL UNIQUE REFERENCES bookings(booking_id),
    payment_method VARCHAR(50)   NOT NULL,
    status         VARCHAR(20)   NOT NULL DEFAULT 'pending'
                                 CHECK (status IN ('pending','success','failed','refunded')),
    amount         NUMERIC(10,2) NOT NULL CHECK (amount >= 0),
    gateway_ref    VARCHAR(255),
    paid_at        TIMESTAMP
);

-- ============================================================
-- 17. CANCELLATIONS  (1:1 with Booking)
-- ============================================================
CREATE TABLE cancellations (
    cancel_id     SERIAL      PRIMARY KEY,
    booking_id    INT         NOT NULL UNIQUE REFERENCES bookings(booking_id),
    reason        TEXT        NOT NULL,
    refund_status VARCHAR(20) NOT NULL DEFAULT 'pending'
                              CHECK (refund_status IN ('pending','processed','not_applicable'))
);

-- ============================================================
-- 18. PROVIDER DOCUMENTS  (Weak entity of ServiceProvider)
-- ============================================================
CREATE TABLE provider_documents (
    document_id   SERIAL       PRIMARY KEY,
    provider_id   INT          NOT NULL REFERENCES service_providers(provider_id) ON DELETE CASCADE,
    document_type VARCHAR(100) NOT NULL,
    file_url      VARCHAR(500) NOT NULL,
    uploaded_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    description   TEXT
);

-- ============================================================
-- 19. PROVIDER AVAILABILITY  (Weak entity of ServiceProvider)
-- ============================================================
CREATE TABLE provider_availability (
    provider_id  INT         NOT NULL REFERENCES service_providers(provider_id) ON DELETE CASCADE,
    day_of_week  VARCHAR(10) NOT NULL CHECK (day_of_week IN
                             ('Monday','Tuesday','Wednesday','Thursday',
                              'Friday','Saturday','Sunday')),
    start_time   TIME        NOT NULL,
    end_time     TIME        NOT NULL,
    CHECK (end_time > start_time),
    PRIMARY KEY (provider_id, day_of_week)
);

-- ============================================================
-- 20. PROVIDER REVIEWS  (Weak entity — tied to Booking)
-- ============================================================
CREATE TABLE provider_reviews (
    review_id  SERIAL  PRIMARY KEY,
    booking_id INT     NOT NULL UNIQUE REFERENCES bookings(booking_id),
    rating     INT     NOT NULL CHECK (rating BETWEEN 1 AND 5),
    comment    TEXT
);

-- ============================================================
-- 21. SERVICE REVIEWS  (Weak entity — tied to Booking)
-- ============================================================
CREATE TABLE service_reviews (
    review_id   SERIAL PRIMARY KEY,
    service_id  INT    NOT NULL REFERENCES services(service_id),
    booking_id  INT    NOT NULL REFERENCES bookings(booking_id),
    rating      INT    NOT NULL CHECK (rating BETWEEN 1 AND 5),
    comment     TEXT,
    UNIQUE (service_id, booking_id)
);

-- ============================================================
-- 22. COMPLAINTS (Fixed: strictly 3NF)
-- ============================================================
CREATE TABLE complaints (
    complaint_id INT          GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    booking_id   INT          NOT NULL REFERENCES bookings(booking_id),
    subject      VARCHAR(255) NOT NULL,
    status       VARCHAR(30)  NOT NULL DEFAULT 'open'
                              CHECK (status IN ('open','in_progress','resolved','closed'))
);

-- ============================================================
--  INDEXES
-- ============================================================
CREATE INDEX idx_bookings_customer   ON bookings(customer_id);
CREATE INDEX idx_bookings_provider   ON bookings(provider_id);
CREATE INDEX idx_bookings_status     ON bookings(status);
CREATE INDEX idx_bookings_date       ON bookings(scheduled_date);
CREATE INDEX idx_services_category   ON services(category_id);
CREATE INDEX idx_areas_city          ON areas(city_id);
CREATE INDEX idx_locations_area      ON locations(area_id);
CREATE INDEX idx_cust_addr_customer  ON customer_addresses(customer_id);
CREATE INDEX idx_prov_docs_provider  ON provider_documents(provider_id);
CREATE INDEX idx_svc_reviews_svc     ON service_reviews(service_id);
CREATE INDEX idx_complaints_status   ON complaints(status);

-- ============================================================
--  END OF DDL
-- ============================================================



-- ============================================================
--  SEVAK – Stored Procedures & Triggers
--  IT214 DBMS Project
-- ============================================================
SET search_path = sevak;


-- ============================================================
--  TRIGGER 1: Auto-Update Provider avg_rating
-- ============================================================
-- PURPOSE: Every time a new review is inserted into
--          provider_reviews, this trigger automatically
--          recalculates the provider's average rating
--          from ALL their reviews and updates the
--          avg_rating column in service_providers.
--
-- WHY THIS IS IMPORTANT:
--   Without this trigger, avg_rating would be stale
--   and require manual recalculation. This ensures
--   real-time data consistency at the database layer.
-- ============================================================

-- Step 1: Create the trigger function
CREATE OR REPLACE FUNCTION fn_update_provider_avg_rating()
RETURNS TRIGGER AS $$
DECLARE
    v_provider_id INT;
    v_new_avg     NUMERIC(3,2);
BEGIN
    -- Get the provider_id from the booking that was reviewed
    SELECT b.provider_id INTO v_provider_id
    FROM bookings b
    WHERE b.booking_id = NEW.booking_id;

    -- Recalculate average rating from ALL reviews for this provider
    SELECT ROUND(AVG(pr.rating), 2) INTO v_new_avg
    FROM provider_reviews pr
    JOIN bookings b ON pr.booking_id = b.booking_id
    WHERE b.provider_id = v_provider_id;

    -- Update the provider's avg_rating
    UPDATE service_providers
    SET avg_rating = v_new_avg
    WHERE provider_id = v_provider_id;

    RAISE NOTICE 'Provider % avg_rating updated to %', v_provider_id, v_new_avg;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Step 2: Attach the trigger to the provider_reviews table
CREATE TRIGGER trg_after_review_insert
AFTER INSERT ON provider_reviews
FOR EACH ROW
EXECUTE FUNCTION fn_update_provider_avg_rating();


-- ============================================================
--  TRIGGER 2: Auto-Log Booking Status Changes
-- ============================================================
-- PURPOSE: Every time a booking's status is updated,
--          this trigger automatically inserts a record
--          into the booking_status_log table.
--
-- WHY THIS IS IMPORTANT:
--   Maintains a complete audit trail of every status
--   transition without the application needing to
--   remember to log it manually.
-- ============================================================

CREATE OR REPLACE FUNCTION fn_log_booking_status_change()
RETURNS TRIGGER AS $$
BEGIN
    -- Only log if the status actually changed
    IF OLD.status IS DISTINCT FROM NEW.status THEN
        INSERT INTO booking_status_log (booking_id, log_time, status, remarks)
        VALUES (
            NEW.booking_id,
            NOW(),
            NEW.status,
            'Status changed from ' || OLD.status || ' to ' || NEW.status
        );

        RAISE NOTICE 'Booking % status logged: % -> %',
            NEW.booking_id, OLD.status, NEW.status;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_after_booking_status_update
AFTER UPDATE OF status ON bookings
FOR EACH ROW
EXECUTE FUNCTION fn_log_booking_status_change();


-- ============================================================
--  PROCEDURE 1: Process Booking Cancellation
-- ============================================================
-- PURPOSE: A single stored procedure that handles the
--          entire cancellation workflow:
--          1. Validates the booking exists and is cancellable
--          2. Updates the booking status to 'cancelled'
--          3. Inserts a row into the cancellations table
--          4. If a successful payment exists, marks it
--             as 'refunded' and sets refund_status
--          All wrapped in a TRANSACTION for ACID compliance.
--
-- USAGE:  CALL sp_cancel_booking(1, 'Customer changed plans');
-- ============================================================

CREATE OR REPLACE PROCEDURE sp_cancel_booking(
    p_booking_id INT,
    p_reason     TEXT
)
LANGUAGE plpgsql
AS $$
DECLARE
    v_current_status VARCHAR(30);
    v_payment_exists BOOLEAN;
BEGIN
    -- Step 1: Validate – Check if booking exists and get current status
    SELECT status INTO v_current_status
    FROM bookings
    WHERE booking_id = p_booking_id;

    IF v_current_status IS NULL THEN
        RAISE EXCEPTION 'Booking ID % does not exist.', p_booking_id;
    END IF;

    -- Step 2: Validate – Cannot cancel already completed or cancelled bookings
    IF v_current_status IN ('completed', 'cancelled') THEN
        RAISE EXCEPTION 'Booking % cannot be cancelled. Current status: %',
            p_booking_id, v_current_status;
    END IF;

    -- Step 3: Update booking status to 'cancelled'
    UPDATE bookings
    SET status = 'cancelled'
    WHERE booking_id = p_booking_id;
    -- (This fires trg_after_booking_status_update automatically!)

    -- Step 4: Check if a successful payment exists
    SELECT EXISTS (
        SELECT 1 FROM payments
        WHERE booking_id = p_booking_id AND status = 'success'
    ) INTO v_payment_exists;

    -- Step 5: Insert cancellation record with appropriate refund status
    IF v_payment_exists THEN
        -- Payment was made → mark for refund
        INSERT INTO cancellations (booking_id, reason, refund_status)
        VALUES (p_booking_id, p_reason, 'pending');

        -- Update the payment to refunded
        UPDATE payments
        SET status = 'refunded'
        WHERE booking_id = p_booking_id AND status = 'success';

        RAISE NOTICE 'Booking % cancelled. Payment refund initiated.', p_booking_id;
    ELSE
        -- No payment was made → no refund needed
        INSERT INTO cancellations (booking_id, reason, refund_status)
        VALUES (p_booking_id, p_reason, 'not_applicable');

        RAISE NOTICE 'Booking % cancelled. No payment to refund.', p_booking_id;
    END IF;
END;
$$;


-- ============================================================
--  PROCEDURE 2: Assign Provider to a Booking
-- ============================================================
-- PURPOSE: When a booking is in 'pending' status, this
--          procedure:
--          1. Validates the provider offers the requested service
--          2. Validates the provider is available on that day
--          3. Confirms the booking by changing status
--          Demonstrates complex validation logic with
--          multiple table lookups in a single procedure.
--
-- USAGE:  CALL sp_confirm_booking(6);
-- ============================================================

CREATE OR REPLACE PROCEDURE sp_confirm_booking(
    p_booking_id INT
)
LANGUAGE plpgsql
AS $$
DECLARE
    v_current_status  VARCHAR(30);
    v_provider_id     INT;
    v_scheduled_day   VARCHAR(10);
    v_scheduled_time  TIME;
    v_is_available    BOOLEAN;
    v_offers_service  BOOLEAN;
    v_service_id      INT;
BEGIN
    -- Step 1: Get booking details
    SELECT b.status, b.provider_id, 
           TRIM(TO_CHAR(b.scheduled_date, 'Day')),
           b.scheduled_time
    INTO v_current_status, v_provider_id, v_scheduled_day, v_scheduled_time
    FROM bookings b
    WHERE b.booking_id = p_booking_id;

    IF v_current_status IS NULL THEN
        RAISE EXCEPTION 'Booking ID % does not exist.', p_booking_id;
    END IF;

    IF v_current_status <> 'pending' THEN
        RAISE EXCEPTION 'Booking % is not pending. Current status: %',
            p_booking_id, v_current_status;
    END IF;

    -- Step 2: Get the first service in this booking
    SELECT bi.service_id INTO v_service_id
    FROM booking_items bi
    WHERE bi.booking_id = p_booking_id
    LIMIT 1;

    -- Step 3: Verify provider offers this service
    SELECT EXISTS (
        SELECT 1 FROM provider_services ps
        WHERE ps.provider_id = v_provider_id
          AND ps.service_id  = v_service_id
    ) INTO v_offers_service;

    IF NOT v_offers_service THEN
        RAISE EXCEPTION 'Provider % does not offer service ID %.',
            v_provider_id, v_service_id;
    END IF;

    -- Step 4: Verify provider is available on the scheduled day and time
    SELECT EXISTS (
        SELECT 1 FROM provider_availability pa
        WHERE pa.provider_id = v_provider_id
          AND pa.day_of_week = v_scheduled_day
          AND pa.start_time <= v_scheduled_time
          AND pa.end_time   >= v_scheduled_time
    ) INTO v_is_available;

    IF NOT v_is_available THEN
        RAISE EXCEPTION 'Provider % is not available on % at %.',
            v_provider_id, v_scheduled_day, v_scheduled_time;
    END IF;

    -- Step 5: Confirm the booking
    UPDATE bookings
    SET status = 'confirmed'
    WHERE booking_id = p_booking_id;
    -- (This fires trg_after_booking_status_update automatically!)

    RAISE NOTICE 'Booking % confirmed. Provider % assigned for % at %.',
        p_booking_id, v_provider_id, v_scheduled_day, v_scheduled_time;
END;
$$;


-- ============================================================
--  TEST CASES (Run these to demonstrate the triggers & procs)
-- ============================================================

-- TEST 1: Insert a new review → trigger should auto-update avg_rating
-- Before: Check provider 1's current avg_rating
-- SELECT provider_id, avg_rating FROM service_providers WHERE provider_id = 1;

-- Insert a new review for booking 4 (provider 1, confirmed booking)
-- First update booking 4 to 'completed' so review makes sense:
-- UPDATE bookings SET status = 'completed' WHERE booking_id = 4;
-- INSERT INTO provider_reviews (booking_id, rating, comment)
-- VALUES (4, 3, 'Average work, took longer than expected.');

-- After: avg_rating should now be recalculated
-- SELECT provider_id, avg_rating FROM service_providers WHERE provider_id = 1;


-- TEST 2: Cancel booking 6 (pending, no payment) using the procedure
-- CALL sp_cancel_booking(6, 'Customer found a cheaper alternative.');

-- TEST 3: Confirm booking 10 (pending) using the procedure
-- CALL sp_confirm_booking(10);

-- TEST 4: Check the auto-generated audit trail
-- SELECT * FROM booking_status_log ORDER BY booking_id, log_time;


-- ============================================================
--  END OF PROCEDURES & TRIGGERS
-- ============================================================
