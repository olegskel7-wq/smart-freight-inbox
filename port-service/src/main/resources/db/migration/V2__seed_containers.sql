-- Demo containers at Port of Felixstowe (ISO 6346 format: 4 letters + 7 digits)
INSERT INTO container (container_number, status, eta, location, updated_at) VALUES
    ('MSCU1234567', 'IN_TRANSIT',  '2026-04-20 08:00:00', 'Vessel MSC Francesca, Bay 14',          now()),
    ('HLXU8764321', 'AT_PORT',     '2026-04-10 14:00:00', 'Felixstowe Terminal 2, Row D',          now()),
    ('CMAU5551234', 'CUSTOMS',     '2026-04-12 09:30:00', 'HMRC Examination Bay 3',                now()),
    ('OOLU9876543', 'DELIVERED',   '2026-04-05 11:00:00', 'DHL Depot, Birmingham',                 now()),
    ('EVRU3219876', 'IN_TRANSIT',  '2026-04-25 16:00:00', 'Vessel Ever Gentle, ETA Felixstowe',   now()),
    ('TEMU4448888', 'AT_PORT',     '2026-04-08 07:00:00', 'Felixstowe Terminal 1, Row B',          now()),
    ('CAIU7773330', 'RELEASED',    '2026-04-07 10:00:00', 'Awaiting haulier collection',           now());
