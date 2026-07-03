-- Demo reference data with fixed UUIDs (referenced in README examples).
-- Patients stand in for authenticated users: requests identify themselves via the
-- X-Patient-Id header, which is the documented auth stub for this take-home.

INSERT INTO doctor (id, name, specialty) VALUES
    ('11111111-1111-1111-1111-111111111111', 'Dr. Aisha Rahman', 'General Practice'),
    ('22222222-2222-2222-2222-222222222222', 'Dr. Ben Tan', 'Dermatology'),
    ('33333333-3333-3333-3333-333333333333', 'Dr. Chloe Lim', 'Paediatrics');

INSERT INTO patient (id, name, email) VALUES
    ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'Alice Ng', 'alice.ng@example.com'),
    ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'Bob Ooi', 'bob.ooi@example.com'),
    ('cccccccc-cccc-cccc-cccc-cccccccccccc', 'Carol Wong', 'carol.wong@example.com');
