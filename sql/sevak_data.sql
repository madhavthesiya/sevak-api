-- ============================================================
--  SEVAK – Sample Data Seeding Script
-- ============================================================
SET search_path = sevak;

-- ============================================================
-- 1. CITIES  (5 rows)
-- ============================================================
INSERT INTO cities (city_name, state) VALUES
('Ahmedabad',  'Gujarat'),
('Mumbai',     'Maharashtra'),
('Bangalore',  'Karnataka'),
('Delhi',      'Delhi'),
('Pune',       'Maharashtra');

-- ============================================================
-- 2. USERS  (15 rows: 7 customers + 6 providers + 2 admins)
-- ============================================================
INSERT INTO users (email, password, role, status) VALUES
-- Customers (user_id 1–7)
('aarav.sharma@gmail.com',   'hashed_pass1', 'customer', 'active'),
('priya.patel@gmail.com',    'hashed_pass2', 'customer', 'active'),
('rohan.mehta@gmail.com',    'hashed_pass3', 'customer', 'active'),
('sneha.reddy@gmail.com',    'hashed_pass4', 'customer', 'active'),
('vikram.singh@gmail.com',   'hashed_pass5', 'customer', 'active'),
('neha.gupta@gmail.com',     'hashed_pass6', 'customer', 'active'),
('arjun.desai@gmail.com',    'hashed_pass7', 'customer', 'inactive'),
-- Providers (user_id 8–13)
('ravi.electrician@gmail.com','hashed_pass8',  'provider', 'active'),
('sunita.cleaner@gmail.com', 'hashed_pass9',  'provider', 'active'),
('amit.plumber@gmail.com',   'hashed_pass10', 'provider', 'active'),
('pooja.painter@gmail.com',  'hashed_pass11', 'provider', 'active'),
('karan.carpenter@gmail.com','hashed_pass12', 'provider', 'active'),
('meena.tutor@gmail.com',    'hashed_pass13', 'provider', 'inactive'),
-- Admins (user_id 14–15)
('admin.raj@sevak.com',      'hashed_admin1', 'admin',    'active'),
('admin.divya@sevak.com',    'hashed_admin2', 'admin',    'active');

-- ============================================================
-- 3. CUSTOMERS  (7 rows, linked to user_id 1–7)
-- ============================================================
INSERT INTO customers (user_id, name, phone) VALUES
(1,  'Aarav Sharma',  '9876543210'),
(2,  'Priya Patel',   '9876543211'),
(3,  'Rohan Mehta',   '9876543212'),
(4,  'Sneha Reddy',   '9876543213'),
(5,  'Vikram Singh',  '9876543214'),
(6,  'Neha Gupta',    '9876543215'),
(7,  'Arjun Desai',   '9876543216');

-- ============================================================
-- 4. SERVICE PROVIDERS  (6 rows, linked to user_id 8–13)
-- ============================================================
INSERT INTO service_providers (user_id, city_id, bio, experience_years, avg_rating, verification_status, custom_price, is_active) VALUES
(8,  1, 'Expert electrician with 10+ years of experience.',  10, 4.50, 'verified',  500.00, TRUE),
(9,  1, 'Professional home cleaning services.',               5, 4.20, 'verified',  300.00, TRUE),
(10, 2, 'Licensed plumber for residential & commercial.',     8, 4.70, 'verified',  600.00, TRUE),
(11, 3, 'Creative wall painter and interior decorator.',       6, 3.90, 'verified',  450.00, TRUE),
(12, 4, 'Skilled carpenter specializing in custom furniture.', 12, 4.80, 'verified',  700.00, TRUE),
(13, 5, 'Home tutor for Mathematics and Science.',             3, 3.50, 'pending',   250.00, FALSE);

-- ============================================================
-- 5. ADMINS  (2 rows, linked to user_id 14–15)
-- ============================================================
INSERT INTO admins (user_id, admin_role, permissions, department) VALUES
(14, 'Super Admin',   'ALL',                'Operations'),
(15, 'Support Admin', 'READ,RESOLVE_TICKET','Customer Support');

-- ============================================================
-- 6. AREAS  (10 rows)
-- ============================================================
INSERT INTO areas (city_id, area_name, pincode) VALUES
(1, 'Navrangpura',   '380009'),
(1, 'Satellite',     '380015'),
(2, 'Andheri West',  '400058'),
(2, 'Bandra East',   '400051'),
(3, 'Koramangala',   '560034'),
(3, 'Indiranagar',   '560038'),
(4, 'Connaught Place','110001'),
(4, 'Dwarka',        '110075'),
(5, 'Kothrud',       '411038'),
(5, 'Viman Nagar',   '411014');

-- ============================================================
-- 7. LOCATIONS  (10 rows — physical places)
-- ============================================================
INSERT INTO locations (area_id, street, landmark, latitude, longitude) VALUES
(1,  '12, Nehru Road',         'Near CG Road',           23.0300000, 72.5700000),
(2,  '45, SG Highway',         'Opp. Iscon Temple',      23.0225000, 72.5300000),
(3,  '78, Link Road',          'Near Andheri Station',    19.1360000, 72.8370000),
(4,  'A-301, Bandra Complex',  'Near Lilavati Hospital',  19.0600000, 72.8400000),
(5,  '56, 1st Cross Road',     'Koramangala 5th Block',   12.9350000, 77.6200000),
(7,  '10, Janpath Lane',       'Near Palika Bazaar',      28.6330000, 77.2190000),
(8,  'D-22, Sector 10',        'Dwarka Metro Station',    28.5870000, 77.0580000),
(9,  '34, Paud Road',          'Near Kothrud Bus Stop',   18.5070000, 73.8070000),
(10, '89, Airport Road',       'Near Pheonix Mall',       18.5670000, 73.9140000),
(5,  '12, 80 Feet Road',       'Koramangala 4th Block',   12.9280000, 77.6220000);

-- ============================================================
-- 7b. CUSTOMER ADDRESSES  (10 rows — maps customers to locations)
-- ============================================================
INSERT INTO customer_addresses (customer_id, location_id, label) VALUES
(1, 1,  'Home'),
(1, 2,  'Office'),
(2, 3,  'Home'),
(3, 4,  'Home'),
(4, 5,  'Home'),
(5, 6,  'Home'),
(5, 7,  'Office'),
(6, 8,  'Home'),
(7, 9,  'Home'),
(3, 10, 'Office');

-- ============================================================
-- 8. CATEGORIES  (6 rows)
-- ============================================================
INSERT INTO categories (category_name, description) VALUES
('Electrical',       'Wiring, appliance repair, and electrical installations'),
('Cleaning',         'Home deep cleaning, kitchen cleaning, bathroom cleaning'),
('Plumbing',         'Pipe fitting, leakage repair, bathroom installations'),
('Painting',         'Wall painting, waterproofing, and texture work'),
('Carpentry',        'Furniture repair, custom builds, and wood polishing'),
('Home Tutoring',    'Academic tutoring for school and college students');

-- ============================================================
-- 9. SERVICES  (12 rows)
-- ============================================================
INSERT INTO services (category_id, service_name, base_price, description) VALUES
(1, 'Fan Installation',           200.00, 'Ceiling or wall fan installation'),
(1, 'Full House Wiring',         2500.00, 'Complete house electrical wiring'),
(2, 'Deep Home Cleaning',        1500.00, 'Full house deep cleaning service'),
(2, 'Kitchen Cleaning',           500.00, 'Thorough kitchen deep clean'),
(3, 'Pipe Leakage Repair',        400.00, 'Fix leaking pipes and joints'),
(3, 'Bathroom Fitting',          1200.00, 'Install taps, showers, and fittings'),
(4, 'Single Room Painting',       800.00, 'Paint one room with primer and finish coat'),
(4, 'Full House Painting',       5000.00, 'Complete house interior painting'),
(5, 'Furniture Assembly',         600.00, 'Assemble flat-pack or new furniture'),
(5, 'Door/Window Repair',         350.00, 'Fix hinges, locks, and frames'),
(6, 'Math Tutoring (Grade 10)',   300.00, 'CBSE Grade 10 Mathematics'),
(6, 'Science Tutoring (Grade 12)',400.00, 'CBSE Grade 12 Physics and Chemistry');

-- ============================================================
-- 10. SERVICE VARIANTS  (10 rows)
-- ============================================================
INSERT INTO service_variants (service_id, variant_name, price, duration) VALUES
(1,  'Standard',     200.00,  30),
(1,  'Premium',      350.00,  45),
(3,  '1 BHK',       1500.00, 120),
(3,  '2 BHK',       2200.00, 180),
(3,  '3 BHK',       3000.00, 240),
(5,  'Minor Fix',    400.00,  30),
(5,  'Major Repair', 800.00,  90),
(7,  'Standard',     800.00, 240),
(7,  'Premium',     1200.00, 300),
(9,  'Basic',        600.00,  60);

-- ============================================================
-- 11. PROVIDER SERVICES  (M:N – which providers offer which services)
-- ============================================================
INSERT INTO provider_services (provider_id, service_id) VALUES
(1, 1),   -- Ravi offers Fan Installation
(1, 2),   -- Ravi offers Full House Wiring
(2, 3),   -- Sunita offers Deep Home Cleaning
(2, 4),   -- Sunita offers Kitchen Cleaning
(3, 5),   -- Amit offers Pipe Leakage Repair
(3, 6),   -- Amit offers Bathroom Fitting
(4, 7),   -- Pooja offers Single Room Painting
(4, 8),   -- Pooja offers Full House Painting
(5, 9),   -- Karan offers Furniture Assembly
(5, 10),  -- Karan offers Door/Window Repair
(6, 11),  -- Meena offers Math Tutoring
(6, 12);  -- Meena offers Science Tutoring

-- ============================================================
-- 12. COUPONS  (5 rows)
-- ============================================================
INSERT INTO coupons (code, discount_type, discount_value, min_order, usage_limit, valid_from, valid_to) VALUES
('WELCOME10',  'percentage', 10.00, 500.00,  100, '2026-01-01', '2026-12-31'),
('FLAT200',    'flat',       200.00, 1000.00,  50, '2026-03-01', '2026-06-30'),
('SUMMER15',   'percentage', 15.00, 800.00,   30, '2026-04-01', '2026-07-31'),
('MEGA500',    'flat',       500.00, 3000.00,  20, '2026-01-01', '2026-03-31'),
('NEWUSER25',  'percentage', 25.00, 300.00,  200, '2026-01-01', '2026-12-31');

-- ============================================================
-- 13. BOOKINGS  (10 rows)
-- ============================================================
INSERT INTO bookings (customer_id, provider_id, location_id, coupon_id, scheduled_date, scheduled_time, status, total_amount, special_instructions) VALUES
(1, 1, 1,  1,    '2026-04-05', '10:00', 'completed',   180.00, 'Please bring own ladder'),
(1, 2, 2,  NULL, '2026-04-06', '09:00', 'completed',  1500.00, 'Focus on kitchen and bathrooms'),
(2, 3, 3,  2,    '2026-04-07', '11:00', 'completed',   200.00, 'Leaking pipe in bathroom'),
(3, 1, 4,  NULL, '2026-04-08', '14:00', 'confirmed',   350.00, NULL),
(4, 4, 5,  3,    '2026-04-09', '10:30', 'in_progress', 680.00, 'Light blue color preferred'),
(5, 5, 6,  NULL, '2026-04-10', '16:00', 'pending',     600.00, 'Assemble IKEA wardrobe'),
(6, 2, 8,  5,    '2026-04-11', '08:00', 'completed',  1125.00, 'Deep clean before house party'),
(3, 5, 10, NULL, '2026-04-12', '12:00', 'cancelled',   350.00, 'Repair broken window latch'),
(2, 4, 3,  NULL, '2026-04-13', '15:00', 'completed',  5000.00, 'Full flat painting – neutral tones'),
(7, 1, 9,  1,    '2026-04-14', '11:00', 'pending',    2250.00, 'Full house rewiring needed');

-- ============================================================
-- 14. BOOKING ITEMS  (15 rows)
-- ============================================================
INSERT INTO booking_items (booking_id, item_no, service_id, variant_id, quantity, unit_price) VALUES
(1,  1, 1,  1,    1, 200.00),    -- Booking 1: Fan Installation – Standard
(2,  1, 3,  3,    1, 1500.00),   -- Booking 2: Deep Cleaning – 1 BHK
(3,  1, 5,  6,    1, 400.00),    -- Booking 3: Pipe Leakage – Minor Fix
(4,  1, 1,  2,    1, 350.00),    -- Booking 4: Fan Installation – Premium
(5,  1, 7,  8,    1, 800.00),    -- Booking 5: Room Painting – Standard
(6,  1, 9,  10,   1, 600.00),    -- Booking 6: Furniture Assembly – Basic
(7,  1, 3,  4,    1, 2200.00),   -- Booking 7: Deep Cleaning – 2 BHK
(7,  2, 4,  NULL, 1, 500.00),    -- Booking 7: + Kitchen Cleaning (no variant)
(8,  1, 10, NULL, 1, 350.00),    -- Booking 8: Door/Window Repair
(9,  1, 8,  NULL, 1, 5000.00),   -- Booking 9: Full House Painting
(10, 1, 2,  NULL, 1, 2500.00),   -- Booking 10: Full House Wiring
(1,  2, 1,  2,    1, 350.00),    -- Booking 1: extra Premium fan
(5,  2, 7,  9,    1, 1200.00),   -- Booking 5: extra Premium room
(3,  2, 6,  NULL, 1, 1200.00),   -- Booking 3: + Bathroom Fitting
(2,  2, 4,  NULL, 1, 500.00);    -- Booking 2: + Kitchen Cleaning

-- ============================================================
-- 15. BOOKING STATUS LOG  (12 rows)
-- ============================================================
INSERT INTO booking_status_log (booking_id, log_time, status, remarks) VALUES
(1,  '2026-04-05 10:00:00', 'pending',     'Booking placed by customer'),
(1,  '2026-04-05 10:05:00', 'confirmed',   'Provider accepted'),
(1,  '2026-04-05 10:30:00', 'in_progress', 'Provider en route'),
(1,  '2026-04-05 11:00:00', 'completed',   'Service finished'),
(2,  '2026-04-06 09:00:00', 'pending',     'Booking placed'),
(2,  '2026-04-06 09:10:00', 'confirmed',   'Provider accepted'),
(2,  '2026-04-06 12:00:00', 'completed',   'Cleaning done'),
(3,  '2026-04-07 11:00:00', 'pending',     'Booking placed'),
(3,  '2026-04-07 11:15:00', 'confirmed',   'Provider accepted'),
(3,  '2026-04-07 12:30:00', 'completed',   'Leak fixed'),
(8,  '2026-04-12 12:00:00', 'pending',     'Booking placed'),
(8,  '2026-04-12 12:30:00', 'cancelled',   'Customer cancelled – not needed');

-- ============================================================
-- 16. PAYMENTS  (8 rows – 1:1 with completed/in-progress bookings)
-- ============================================================
INSERT INTO payments (booking_id, payment_method, status, amount, gateway_ref, paid_at) VALUES
(1,  'UPI',         'success',  180.00,  'TXN_UPI_001',  '2026-04-05 11:05:00'),
(2,  'Credit Card', 'success', 1500.00,  'TXN_CC_002',   '2026-04-06 12:05:00'),
(3,  'UPI',         'success',  200.00,  'TXN_UPI_003',  '2026-04-07 12:35:00'),
(5,  'Debit Card',  'pending',  680.00,  NULL,            NULL),
(7,  'Net Banking', 'success', 1125.00,  'TXN_NB_007',   '2026-04-11 11:30:00'),
(9,  'UPI',         'success', 5000.00,  'TXN_UPI_009',  '2026-04-13 17:00:00'),
(10, 'Credit Card', 'pending', 2250.00,  NULL,            NULL),
(8,  'UPI',         'refunded', 350.00,  'TXN_UPI_008',  '2026-04-12 13:00:00');

-- ============================================================
-- 17. CANCELLATIONS  (1 row for booking 8)
-- ============================================================
INSERT INTO cancellations (booking_id, reason, refund_status) VALUES
(8, 'Customer decided the repair was not needed after all.', 'processed');

-- ============================================================
-- 18. PROVIDER DOCUMENTS  (6 rows)
-- ============================================================
INSERT INTO provider_documents (provider_id, document_type, file_url, description) VALUES
(1, 'Aadhaar Card',  '/docs/ravi_aadhaar.pdf',   'Government ID proof'),
(1, 'Certificate',   '/docs/ravi_electrician_cert.pdf', 'Electrician license'),
(2, 'Aadhaar Card',  '/docs/sunita_aadhaar.pdf',  'Government ID proof'),
(3, 'PAN Card',      '/docs/amit_pan.pdf',         'Tax ID proof'),
(4, 'Aadhaar Card',  '/docs/pooja_aadhaar.pdf',    'Government ID proof'),
(5, 'Aadhaar Card',  '/docs/karan_aadhaar.pdf',    'Government ID proof');

-- ============================================================
-- 19. PROVIDER AVAILABILITY  (12 rows)
-- ============================================================
INSERT INTO provider_availability (provider_id, day_of_week, start_time, end_time) VALUES
(1, 'Monday',    '09:00', '18:00'),
(1, 'Tuesday',   '09:00', '18:00'),
(1, 'Wednesday', '09:00', '18:00'),
(1, 'Saturday',  '10:00', '14:00'),
(2, 'Monday',    '08:00', '16:00'),
(2, 'Wednesday', '08:00', '16:00'),
(2, 'Friday',    '08:00', '16:00'),
(3, 'Monday',    '10:00', '19:00'),
(3, 'Thursday',  '10:00', '19:00'),
(4, 'Tuesday',   '09:00', '17:00'),
(4, 'Friday',    '09:00', '17:00'),
(5, 'Monday',    '11:00', '20:00');

-- ============================================================
-- 20. PROVIDER REVIEWS  (5 rows – only for completed bookings)
-- ============================================================
INSERT INTO provider_reviews (booking_id, rating, comment) VALUES
(1, 5, 'Excellent work! Fan installed perfectly and quickly.'),
(2, 4, 'Good cleaning but slightly late arrival.'),
(3, 5, 'Fixed the leak in under 30 minutes. Very professional.'),
(7, 4, 'Thorough cleaning, house looks brand new.'),
(9, 3, 'Decent painting job but missed some spots near the ceiling.');

-- ============================================================
-- 21. SERVICE REVIEWS  (6 rows)
-- ============================================================
INSERT INTO service_reviews (service_id, booking_id, rating, comment) VALUES
(1, 1, 5, 'Fan Installation service was quick and efficient.'),
(3, 2, 4, 'Deep cleaning was satisfactory.'),
(5, 3, 5, 'Pipe repair was handled expertly.'),
(3, 7, 5, 'Best cleaning service I have ever used!'),
(8, 9, 3, 'Painting quality could be improved.'),
(4, 2, 4, 'Kitchen cleaning was thorough.');

-- ============================================================
-- 22. COMPLAINTS  (3 rows)
-- ============================================================
INSERT INTO complaints (booking_id, subject, status) VALUES
(5,  'Painter arrived 1 hour late',           'in_progress'),
(8,  'Refund not received after cancellation', 'resolved'),
(9,  'Paint peeling off after 2 days',         'open');

-- ============================================================
--  END OF DATA SEEDING
-- ============================================================
