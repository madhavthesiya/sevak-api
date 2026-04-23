# SEVAK — Normalization Proof Document

## IT214 Database Management System | Winter 2026
### Proof that the SEVAK Schema is in 3NF / BCNF

---

## 1. Normalization Overview

| Normal Form | Rule | SEVAK Status |
|-------------|------|:------------:|
| **1NF** | All attributes are atomic (no arrays, no repeating groups) | ✅ Satisfied |
| **2NF** | No partial dependency (non-key attributes depend on the FULL primary key) | ✅ Satisfied |
| **3NF** | No transitive dependency (non-key attributes depend ONLY on the primary key) | ✅ Satisfied |
| **BCNF** | Every determinant is a candidate key | ✅ Satisfied |

---

## 2. Definitions (For Viva Reference)

- **Functional Dependency (FD):** A → B means "if you know A, you can uniquely determine B."
- **Partial Dependency:** A non-key attribute depends on only PART of a composite primary key. (Violates 2NF)
- **Transitive Dependency:** A non-key attribute depends on ANOTHER non-key attribute instead of the primary key. (Violates 3NF)
- **BCNF:** For every functional dependency X → Y, X must be a superkey. (Stricter than 3NF)

---

## 3. Table-by-Table Normalization Proof

### Table 1: users
```
Primary Key: user_id
```

| Functional Dependency | Explanation |
|----------------------|-------------|
| user_id → email, password, role, status, created_at | All attributes depend directly on user_id |
| email → user_id | Candidate key (UNIQUE constraint) |

- **1NF:** All attributes are atomic. ✅
- **2NF:** Single-column PK, no partial dependency possible. ✅
- **3NF:** No non-key attribute depends on another non-key attribute. `role` does not determine `status`; `status` does not determine `email`. ✅
- **BCNF:** Both determinants (`user_id`, `email`) are candidate keys. ✅

---

### Table 2: customers
```
Primary Key: customer_id
Candidate Key: user_id (UNIQUE)
Foreign Key: user_id → users(user_id)
```

| Functional Dependency | Explanation |
|----------------------|-------------|
| customer_id → user_id, name, phone | All attributes depend on customer_id |
| user_id → customer_id, name, phone | Alternate candidate key |

- **1NF:** Atomic values. `name` is a single string, not split into first/last (acceptable for this domain). ✅
- **2NF:** Single-column PK. ✅
- **3NF:** `name` and `phone` do NOT depend on each other — both depend directly on `customer_id`. ✅
- **BCNF:** Both determinants are candidate keys. ✅

---

### Table 3: cities
```
Primary Key: city_id
```

| Functional Dependency | Explanation |
|----------------------|-------------|
| city_id → city_name, state | All attributes depend on city_id |

- **1NF:** Atomic. ✅
- **2NF:** Single-column PK. ✅
- **3NF:** `state` does NOT depend on `city_name`. Multiple cities can share the same state (e.g., Mumbai & Pune → Maharashtra). No transitive dependency. ✅
- **BCNF:** Only determinant is `city_id` (superkey). ✅

---

### Table 4: service_providers
```
Primary Key: provider_id
Candidate Key: user_id (UNIQUE)
Foreign Key: user_id → users, city_id → cities
```

| Functional Dependency | Explanation |
|----------------------|-------------|
| provider_id → user_id, city_id, bio, experience_years, avg_rating, verification_status, custom_price, is_active | All depend on PK |

- **1NF:** All attributes are atomic. ✅
- **2NF:** Single-column PK. ✅
- **3NF Check — Why `city_id` is NOT a transitive dependency:**
  - `city_id` is a foreign key reference, not a derived value. The provider's city is a direct fact about the provider.
  - We do NOT store `city_name` or `state` in this table — those live in the `cities` table. Hence no transitivity. ✅
- **BCNF:** Only determinant is `provider_id` (superkey). ✅

---

### Table 5: admins
```
Primary Key: admin_id
Candidate Key: user_id (UNIQUE)
Foreign Key: user_id → users
```

| Functional Dependency | Explanation |
|----------------------|-------------|
| admin_id → user_id, admin_role, permissions, department | All depend on PK |

- **1NF:** ✅  |  **2NF:** ✅  |  **3NF:** `department` does not determine `permissions`. ✅  |  **BCNF:** ✅

---

### Table 6: areas
```
Primary Key: area_id
Foreign Key: city_id → cities
```

| Functional Dependency | Explanation |
|----------------------|-------------|
| area_id → city_id, area_name, pincode | All depend on PK |

- **3NF Check:** `pincode` does NOT determine `area_name` (multiple areas can share a pincode in India). `city_id` is a FK, not a derived attribute. No transitive dependency. ✅
- **BCNF:** Only determinant is `area_id`. ✅

---

### Table 7: locations
```
Primary Key: location_id
Candidate Key: (latitude, longitude) — UNIQUE constraint
Foreign Key: area_id → areas
```

| Functional Dependency | Explanation |
|----------------------|-------------|
| location_id → area_id, street, landmark, latitude, longitude | All depend on PK |
| (latitude, longitude) → location_id, area_id, street, landmark | Candidate key (UNIQUE GPS coordinates) |

- **1NF:** Each attribute is atomic. Latitude and longitude are stored as separate numeric fields. ✅
- **2NF:** Single-column PK. ✅
- **3NF Check — Critical (BCNF Fix):** In an earlier version, this data was stored in an `addresses` table alongside `customer_id` and `label`. The dependency `{latitude, longitude} → {area_id, street, landmark}` violated BCNF because `{latitude, longitude}` was NOT a superkey (multiple customers could share the same GPS coordinates). By extracting the physical location data into this dedicated table, `{latitude, longitude}` is now a candidate key (UNIQUE constraint), making every determinant a superkey. ✅
- **BCNF:** Both `location_id` and `(latitude, longitude)` are candidate keys. ✅

---

### Table 8: customer_addresses (M:N Mapping — Customer ↔ Location)
```
Primary Key: (customer_id, location_id) — Composite
Foreign Keys: customer_id → customers, location_id → locations
```

| Functional Dependency | Explanation |
|----------------------|-------------|
| (customer_id, location_id) → label | The only non-key attribute depends on the FULL composite PK |

- **2NF:** `label` depends on the FULL composite key — the same customer may have different labels for different locations (e.g., "Home" vs "Office"). ✅
- **3NF:** No transitive dependency — `label` does not determine anything else. ✅
- **BCNF:** The only determinant is the composite PK (a superkey). ✅

---

### Table 9: categories
```
Primary Key: category_id
Candidate Key: category_name (UNIQUE)
```

| Functional Dependency | Explanation |
|----------------------|-------------|
| category_id → category_name, description | All depend on PK |
| category_name → category_id, description | Candidate key |

- **BCNF:** Both determinants are candidate keys. ✅

---

### Table 10: services
```
Primary Key: service_id
Foreign Key: category_id → categories
```

| Functional Dependency | Explanation |
|----------------------|-------------|
| service_id → category_id, service_name, base_price, description | All depend on PK |

- **3NF Check:** We store `category_id` (FK), NOT `category_name`. No transitive dependency. ✅
- **BCNF:** ✅

---

### Table 11: service_variants ⚠️ (Weak Entity)
```
Primary Key: variant_id
Composite Candidate Key: (service_id, variant_name) — UNIQUE constraint
Foreign Key: service_id → services
```

| Functional Dependency | Explanation |
|----------------------|-------------|
| variant_id → service_id, variant_name, price, duration | All depend on PK |
| (service_id, variant_name) → variant_id, price, duration | Candidate key |

- **2NF Check (important for composite candidate key):** `price` depends on the FULL combination of `(service_id, variant_name)`, not on `service_id` alone (different variants have different prices). ✅
- **BCNF:** Both determinants are candidate keys. ✅

---

### Table 12: provider_services (M:N Associative Entity)
```
Primary Key: (provider_id, service_id) — Composite
Foreign Keys: provider_id → service_providers, service_id → services
```

| Functional Dependency | Explanation |
|----------------------|-------------|
| (provider_id, service_id) → ∅ | No non-key attributes exist |

- **All Normal Forms Trivially Satisfied:** This table has ONLY key attributes (the composite PK). There are zero non-key attributes, so no partial or transitive dependencies are possible. ✅

---

### Table 13: coupons
```
Primary Key: coupon_id
Candidate Key: code (UNIQUE)
```

| Functional Dependency | Explanation |
|----------------------|-------------|
| coupon_id → code, discount_type, discount_value, min_order, usage_limit, valid_from, valid_to | All depend on PK |

- **3NF Check:** `discount_value` does NOT depend on `discount_type` — knowing the type is "percentage" doesn't tell you the value is 10% or 25%. ✅
- **BCNF:** ✅

---

### Table 14: bookings
```
Primary Key: booking_id
Foreign Keys: customer_id → customers, provider_id → service_providers,
              location_id → locations, coupon_id → coupons
```

| Functional Dependency | Explanation |
|----------------------|-------------|
| booking_id → customer_id, provider_id, location_id, coupon_id, scheduled_date, scheduled_time, status, total_amount, special_instructions | All depend on PK |

- **3NF Check — Critical:** We store only foreign key IDs (`customer_id`, `provider_id`, `location_id`), NOT their names or details. The customer's name, provider's email, and location's street are accessible only via JOINs. This eliminates ALL transitive dependencies. ✅
- **BCNF:** ✅

---

### Table 15: booking_items ⚠️ (Weak Entity — Composite PK)
```
Primary Key: (booking_id, item_no) — Composite
Foreign Keys: service_id → services, variant_id → service_variants
```

| Functional Dependency | Explanation |
|----------------------|-------------|
| (booking_id, item_no) → service_id, variant_id, quantity, unit_price | All depend on FULL composite PK |

- **2NF Check (Critical for composite keys):**
  - `service_id` depends on the FULL key `(booking_id, item_no)`, NOT just `booking_id`. A single booking can have multiple items with different services. ✅
  - `unit_price` depends on the FULL key — different items in the same booking have different prices. ✅
- **3NF Check:** `unit_price` does NOT depend on `service_id`. The unit price is the price at the time of booking, which may differ from the service's base_price. ✅
- **BCNF:** ✅

---

### Table 16: booking_status_log ⚠️ (Weak Entity — Composite PK)
```
Primary Key: (booking_id, log_time) — Composite
```

| Functional Dependency | Explanation |
|----------------------|-------------|
| (booking_id, log_time) → status, remarks | Both depend on FULL composite key |

- **2NF:** `status` depends on the full key — the same booking has different statuses at different times. ✅
- **BCNF:** ✅

---

### Table 17: payments
```
Primary Key: payment_id
Candidate Key: booking_id (UNIQUE — 1:1 relationship)
```

| Functional Dependency | Explanation |
|----------------------|-------------|
| payment_id → booking_id, payment_method, status, amount, gateway_ref, paid_at | All depend on PK |

- **3NF Check:** `gateway_ref` does NOT depend on `payment_method`. Different UPI transactions have different references. ✅
- **BCNF:** Both `payment_id` and `booking_id` are candidate keys. ✅

---

### Table 18: cancellations
```
Primary Key: cancel_id
Candidate Key: booking_id (UNIQUE — 1:1)
```

| Functional Dependency | Explanation |
|----------------------|-------------|
| cancel_id → booking_id, reason, refund_status | All depend on PK |

- **3NF:** `refund_status` does NOT depend on `reason`. ✅
- **BCNF:** ✅

---

### Table 19: provider_documents
```
Primary Key: document_id
Foreign Key: provider_id → service_providers
```

| Functional Dependency | Explanation |
|----------------------|-------------|
| document_id → provider_id, document_type, file_url, uploaded_at, description | All depend on PK |

- **BCNF:** ✅

---

### Table 20: provider_availability ⚠️ (Weak Entity — Composite PK)
```
Primary Key: (provider_id, day_of_week) — Composite
```

| Functional Dependency | Explanation |
|----------------------|-------------|
| (provider_id, day_of_week) → start_time, end_time | Both depend on FULL composite key |

- **2NF:** `start_time` depends on the full key — the same provider can have different start times on different days. ✅
- **BCNF:** ✅

---

### Table 21: provider_reviews
```
Primary Key: review_id
Candidate Key: booking_id (UNIQUE — one review per booking)
```

| Functional Dependency | Explanation |
|----------------------|-------------|
| review_id → booking_id, rating, comment | All depend on PK |

- **3NF Check — Why we removed `customer_id` and `provider_id`:**
  - In an earlier version, this table stored `customer_id` and `provider_id` alongside `booking_id`.
  - Since `booking_id → customer_id` (via the bookings table) and `booking_id → provider_id` (via the bookings table), storing them here created a **transitive dependency**: `review_id → booking_id → customer_id`.
  - **Fix applied:** We removed `customer_id` and `provider_id`. They are now accessible only through a JOIN with the `bookings` table. ✅
- **BCNF:** Both `review_id` and `booking_id` are candidate keys. ✅

---

### Table 22: service_reviews
```
Primary Key: review_id
Composite Candidate Key: (service_id, booking_id) — UNIQUE
Foreign Keys: service_id → services, booking_id → bookings
```

| Functional Dependency | Explanation |
|----------------------|-------------|
| review_id → service_id, booking_id, rating, comment | All depend on PK |

- **3NF Check:** Same logic as provider_reviews — `customer_id` was removed to eliminate transitive dependency through `booking_id`. ✅
- **BCNF:** ✅

---

### Table 23: complaints
```
Primary Key: complaint_id
Foreign Key: booking_id → bookings
```

| Functional Dependency | Explanation |
|----------------------|-------------|
| complaint_id → booking_id, subject, status | All depend on PK |

- **3NF Check:** Same logic — `customer_id` was removed. The customer who filed the complaint is derivable via `booking_id → bookings.customer_id`. ✅
- **BCNF:** ✅

---

## 4. Summary of 3NF Design Decisions

| Design Decision | Why It Ensures 3NF |
|----------------|-------------------|
| City/Area/Location hierarchy via FKs | Avoids storing `city_name` in `locations` (transitive dependency) |
| `locations` + `customer_addresses` split | Fixes BCNF violation: `{lat, lng} → {area, street}` where `{lat, lng}` was not a superkey in the old combined `addresses` table |
| `provider_reviews` has only `booking_id` | `customer_id` and `provider_id` are derivable via JOIN — eliminates transitive dependency |
| `complaints` has only `booking_id` | Same as above — `customer_id` removed |
| `service_reviews` has only `booking_id` + `service_id` | `customer_id` removed |
| `bookings` stores only FK IDs | Customer name, provider email, location street — all accessible via JOINs only |
| `provider_services` (M:N table) | Has zero non-key attributes — trivially in BCNF |
| `booking_items` stores `unit_price` separately | Price at booking time may differ from `services.base_price` — not a derived attribute |

---

## 5. Conclusion

All 23 tables in the SEVAK database satisfy:
- **1NF:** Every attribute stores a single atomic value. No repeating groups or arrays.
- **2NF:** Every non-key attribute depends on the FULL primary key (critical for composite keys in `booking_items`, `booking_status_log`, `provider_availability`).
- **3NF:** No non-key attribute transitively depends on another non-key attribute. All derivable information (customer names, provider emails, city names) is accessed via JOIN operations, never stored redundantly.
- **BCNF:** Every functional dependency's determinant is a candidate key.

**The SEVAK schema is in Boyce-Codd Normal Form (BCNF).**

---

*Document prepared for IT214 DBMS Project — Winter 2026*
*Application: SEVAK — Service Marketplace Database Management System*
