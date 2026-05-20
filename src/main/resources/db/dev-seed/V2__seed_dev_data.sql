-- Dev-only seed: demo containers and the operator user.
-- This migration runs only when the 'dev' Spring profile is active
-- (spring.flyway.locations includes classpath:db/migration/dev).
-- Do NOT run in production — production users are created via the admin UI.

-- Demo containers for testing the pipeline.
-- Container numbers follow ISO 6346 format: 4 letters + 7 digits.
INSERT INTO container (container_number, status, eta, location) VALUES
    ('MSCU1234567', 'IN_TRANSIT',  '2026-04-20 08:00:00', 'Vessel MSC Francesca, Bay 14'),
    ('HLXU8764321', 'AT_PORT',     '2026-04-10 14:00:00', 'Felixstowe Terminal 2, Row D'),
    ('CMAU5551234', 'CUSTOMS',     '2026-04-12 09:30:00', 'HMRC Examination Bay 3'),
    ('OOLU9876543', 'DELIVERED',   '2026-04-05 11:00:00', 'DHL Depot, Birmingham'),
    ('EVRU3219876', 'IN_TRANSIT',  '2026-04-25 16:00:00', 'Vessel Ever Gentle, ETA Felixstowe'),
    ('TEMU4448888', 'AT_PORT',     '2026-04-08 07:00:00', 'Felixstowe Terminal 1, Row B'),
    ('CAIU7773330', 'RELEASED',    '2026-04-07 10:00:00', 'Awaiting haulier collection');

-- Demo operator user. Password: "demo123" (BCrypt, cost 10).
-- Squashed from V2 + V3 + V6 — this is the single authoritative seed.
-- Rotate this password before any public deployment.
INSERT INTO app_user (email, password, role) VALUES
    ('operator@smartfreight.com',
     '$2a$10$vDM1KvzVlZ9uQ8CSR.X5k.1qho.9gwoUdOSGIZh2V3LKuXWReWkra',
     'OPERATOR');
