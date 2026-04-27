-- Replace initial 7 containers with 20 diverse containers across UK ports
TRUNCATE TABLE container RESTART IDENTITY;

INSERT INTO container (container_number, status, eta, location, updated_at) VALUES
    ('MSCU1234567', 'IN_TRANSIT',  '2026-04-28 08:00:00', 'Vessel MSC Francesca, Bay 14, ETA Felixstowe',         now()),
    ('HLXU8764321', 'AT_PORT',     '2026-04-18 14:00:00', 'Felixstowe Terminal 2, Row D, Berth 9',                now()),
    ('CMAU5551234', 'CUSTOMS',     '2026-04-15 09:30:00', 'HMRC Examination Bay 3, Felixstowe',                   now()),
    ('OOLU9876543', 'DELIVERED',   '2026-04-05 11:00:00', 'DHL Depot, Birmingham, Unit 4',                        now()),
    ('EVRU3219876', 'IN_TRANSIT',  '2026-05-03 16:00:00', 'Vessel Ever Gentle, Bay 22, ETA Felixstowe',           now()),
    ('TEMU4448888', 'RELEASED',    '2026-04-17 07:00:00', 'Felixstowe Terminal 1, Row B, Gate 3',                 now()),
    ('CAIU7773330', 'DELIVERED',   '2026-04-07 10:00:00', 'XPO Logistics Depot, Coventry',                        now()),
    ('MAEU2938475', 'IN_TRANSIT',  '2026-05-01 06:00:00', 'Vessel Maersk Honam, Deck 3, ETA Southampton',         now()),
    ('CSQU1029384', 'AT_PORT',     '2026-04-19 11:00:00', 'Southampton Container Terminal, Bay 7',                now()),
    ('YMLU5647382', 'CUSTOMS',     '2026-04-16 13:00:00', 'HMRC Examination Bay 1, Southampton',                  now()),
    ('COSU8374610', 'IN_TRANSIT',  '2026-05-07 09:00:00', 'Vessel COSCO Pacific, Bay 41, ETA Tilbury',            now()),
    ('APZU3847561', 'AT_PORT',     '2026-04-20 15:30:00', 'London Tilbury Terminal, Row F, Berth 2',              now()),
    ('NYKU6610293', 'RELEASED',    '2026-04-14 08:00:00', 'Tilbury Container Services, Waiting Collection',       now()),
    ('TRHU9182736', 'DELIVERED',   '2026-04-10 12:00:00', 'Amazon Fulfilment Centre, Dartford',                   now()),
    ('FSCU4455667', 'IN_TRANSIT',  '2026-04-30 10:00:00', 'Vessel Fennia, Bay 8, ETA Liverpool',                  now()),
    ('UACU7788990', 'AT_PORT',     '2026-04-21 09:00:00', 'Liverpool Seaforth Terminal, Row C, Berth 5',          now()),
    ('GLDU2211334', 'CUSTOMS',     '2026-04-19 14:00:00', 'HMRC Examination Bay 2, Liverpool',                    now()),
    ('GESU5566778', 'IN_TRANSIT',  '2026-05-10 07:00:00', 'Vessel Gerd Maersk, Bay 17, ETA Bristol',              now()),
    ('TCKU3344556', 'RELEASED',    '2026-04-13 16:00:00', 'Bristol Royal Portbury Dock, Yard B',                  now()),
    ('SEGU8899001', 'DELIVERED',   '2026-04-08 11:00:00', 'Wincanton Warehouse, Bristol, Bay 12',                 now());
