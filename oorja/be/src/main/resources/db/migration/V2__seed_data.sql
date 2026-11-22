-- =========================================================
-- V2: Seed data (admin, customers, plants, technicians, bookings)
-- Mirrors the data already shown in the Oorja on Move dashboard UI.
-- =========================================================

-- ---------- Admin ----------
INSERT INTO admins (name, email, phone, role) VALUES
    ('Aarav Sharma', 'admin@oorjaonmove.com', '9001122334', 'SUPER_ADMIN');

-- ---------- Customers ----------
INSERT INTO customers (customer_code, name, email, phone, address, status) VALUES
    ('CUS1001', 'Rahul Sharma',   'rahul@gmail.com',   '9876543210', '123, MG Road, Jaipur, Rajasthan - 302001',        'ACTIVE'),
    ('CUS1002', 'Priya Verma',    'priya@gmail.com',   '8765432109', '56, Malviya Nagar, Jaipur, Rajasthan - 302017',   'ACTIVE'),
    ('CUS1003', 'Amit Singh',     'amit@gmail.com',    '7654321098', 'RIICO Ind. Area, Jaipur, Rajasthan - 302013',     'ACTIVE'),
    ('CUS1004', 'Sunita Agarwal', 'sunita@gmail.com',  '6543210987', 'C-Scheme, Jaipur, Rajasthan - 302001',            'ACTIVE'),
    ('CUS1005', 'Vikram Mehta',   'vikram@gmail.com',  '5432109876', 'Sitapura Ind. Area, Jaipur, Rajasthan - 302022',  'INACTIVE'),
    ('CUS1006', 'Neha Gupta',     'neha@gmail.com',    '4321098765', 'Vaishali Nagar, Jaipur, Rajasthan - 302021',      'ACTIVE'),
    ('CUS1007', 'Raj Malhotra',   'raj@gmail.com',     '3210987654', 'Mansarovar, Jaipur, Rajasthan - 302020',          'ACTIVE'),
    ('CUS1008', 'Pooja Nair',     'pooja@gmail.com',   '2109876543', 'Jagatpura, Jaipur, Rajasthan - 302017',           'ACTIVE');

-- ---------- Plants ----------
-- Ownership assigned to the customer most associated with each plant in the mock data.
INSERT INTO plants (plant_code, name, type, capacity_kw, location, status, customer_id) VALUES
    ('PLT01', 'Jaipur Solar Plant', 'COMMERCIAL',  500, 'Sitapura Industrial Area, Jaipur', 'ACTIVE',
        (SELECT id FROM customers WHERE customer_code = 'CUS1006')),
    ('PLT02', 'Verma Residence',    'RESIDENTIAL', 5,   'Malviya Nagar, Jaipur',            'ACTIVE',
        (SELECT id FROM customers WHERE customer_code = 'CUS1002')),
    ('PLT03', 'Amit Industries',    'INDUSTRIAL',  100, 'RIICO Ind. Area, Jaipur',          'ACTIVE',
        (SELECT id FROM customers WHERE customer_code = 'CUS1003')),
    ('PLT04', 'Sunita Villa',       'RESIDENTIAL', 10,  'C-Scheme, Jaipur',                 'ACTIVE',
        (SELECT id FROM customers WHERE customer_code = 'CUS1004')),
    ('PLT05', 'Mehta Factory',      'INDUSTRIAL',  200, 'Sitapura Ind. Area, Jaipur',       'MAINTENANCE',
        (SELECT id FROM customers WHERE customer_code = 'CUS1005'));

-- ---------- Technicians ----------
INSERT INTO technicians (technician_code, name, phone, specialization, area, availability) VALUES
    ('TCH001', 'Ramesh Kumar',     '9811122233', 'Installation & Wiring',    'Jaipur East',  'AVAILABLE'),
    ('TCH002', 'Mahesh Yadav',     '9822233344', 'Inspection & Checkup',     'Jaipur South', 'AVAILABLE'),
    ('TCH003', 'Suresh Pal',       '9833344455', 'Cleaning & Maintenance',   'Jaipur North', 'AVAILABLE'),
    ('TCH004', 'Deepak Choudhary', '9844455566', 'Inverter & Panel Repair',  'Jaipur West',  'AVAILABLE'),
    ('TCH005', 'Vikas Rathore',    '9855566677', 'Installation & Wiring',    'Sitapura',     'AVAILABLE');

-- ---------- Bookings ----------
INSERT INTO bookings (token, customer_id, plant_id, technician_id, service_type, scheduled_date, scheduled_time, status, created_at) VALUES
    ('TKN-26-00231',
        (SELECT id FROM customers WHERE customer_code = 'CUS1001'),
        (SELECT id FROM plants WHERE plant_code = 'PLT01'),
        NULL,
        'Solar Plant Cleaning', DATE '2026-08-18', TIME '10:00', 'PENDING', now()),

    ('TKN-26-00230',
        (SELECT id FROM customers WHERE customer_code = 'CUS1002'),
        (SELECT id FROM plants WHERE plant_code = 'PLT02'),
        (SELECT id FROM technicians WHERE technician_code = 'TCH001'),
        'Preventive Maintenance', DATE '2026-08-17', TIME '14:30', 'ASSIGNED', now() - INTERVAL '1 day'),

    ('TKN-26-00229',
        (SELECT id FROM customers WHERE customer_code = 'CUS1003'),
        (SELECT id FROM plants WHERE plant_code = 'PLT03'),
        (SELECT id FROM technicians WHERE technician_code = 'TCH002'),
        'Inspection & Checkup', DATE '2026-08-16', TIME '11:00', 'ON_SITE', now() - INTERVAL '2 days'),

    ('TKN-26-00228',
        (SELECT id FROM customers WHERE customer_code = 'CUS1004'),
        (SELECT id FROM plants WHERE plant_code = 'PLT04'),
        (SELECT id FROM technicians WHERE technician_code = 'TCH003'),
        'New Solar Installation', DATE '2026-08-15', TIME '09:00', 'COMPLETED', now() - INTERVAL '3 days'),

    ('TKN-26-00227',
        (SELECT id FROM customers WHERE customer_code = 'CUS1005'),
        (SELECT id FROM plants WHERE plant_code = 'PLT05'),
        (SELECT id FROM technicians WHERE technician_code = 'TCH001'),
        'Inverter Replacement', DATE '2026-08-15', TIME '13:00', 'COMPLETED', now() - INTERVAL '3 days'),

    ('TKN-26-00226',
        (SELECT id FROM customers WHERE customer_code = 'CUS1006'),
        (SELECT id FROM plants WHERE plant_code = 'PLT01'),
        (SELECT id FROM technicians WHERE technician_code = 'TCH002'),
        'Preventive Maintenance', DATE '2026-08-14', TIME '16:00', 'COMPLETED', now() - INTERVAL '4 days'),

    ('TKN-26-00225',
        (SELECT id FROM customers WHERE customer_code = 'CUS1007'),
        (SELECT id FROM plants WHERE plant_code = 'PLT03'),
        (SELECT id FROM technicians WHERE technician_code = 'TCH003'),
        'Solar Plant Cleaning', DATE '2026-08-20', TIME '10:30', 'ASSIGNED', now() - INTERVAL '12 hours'),

    ('TKN-26-00224',
        (SELECT id FROM customers WHERE customer_code = 'CUS1008'),
        (SELECT id FROM plants WHERE plant_code = 'PLT04'),
        NULL,
        'Inspection & Checkup', DATE '2026-08-21', TIME '12:00', 'PENDING', now() - INTERVAL '6 hours');

-- Technicians currently on an active job still register as AVAILABLE here —
-- "ON_JOB" is never stored; TechnicianService derives it at read time from
-- active bookings (see enrich()). Writing 'BOOKED' into this column doesn't
-- match any TechnicianAvailability enum constant and breaks every admin
-- endpoint that loads a technician (members/bookings/technicians all 500).
