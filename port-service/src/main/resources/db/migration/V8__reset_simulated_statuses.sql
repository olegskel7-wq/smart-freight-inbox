-- The demo status simulator (StatusUpdateScheduler) is now off by default. While it ran it cycled
-- container statuses without touching location/ETA, so live data contradicts itself
-- (e.g. CMAU5551234 IN_TRANSIT at "HMRC Examination Bay 3"). Put every seeded container back to
-- the status its seed (V3, V7) gave it; location and ETA were never changed by the simulator.
UPDATE container c
   SET status = s.status,
       updated_at = now()
  FROM (VALUES
    ('MSCU1234567',   'IN_TRANSIT'),
    ('HLXU8764321',   'AT_PORT'),
    ('CMAU5551234',   'CUSTOMS'),
    ('OOLU9876543',   'DELIVERED'),
    ('EVRU3219876',   'IN_TRANSIT'),
    ('TEMU4448888',   'RELEASED'),
    ('CAIU7773330',   'DELIVERED'),
    ('MAEU2938475',   'IN_TRANSIT'),
    ('CSQU1029384',   'AT_PORT'),
    ('YMLU5647382',   'CUSTOMS'),
    ('COSU8374610',   'IN_TRANSIT'),
    ('APZU3847561',   'AT_PORT'),
    ('NYKU6610293',   'RELEASED'),
    ('TRHU9182736',   'DELIVERED'),
    ('FSCU4455667',   'IN_TRANSIT'),
    ('UACU7788990',   'AT_PORT'),
    ('GLDU2211334',   'CUSTOMS'),
    ('GESU5566778',   'IN_TRANSIT'),
    ('TCKU3344556',   'RELEASED'),
    ('SEGU8899001',   'DELIVERED'),
    ('MID-2026-0481', 'IN_TRANSIT'),
    ('MID-2026-0492', 'AT_PORT'),
    ('MID-2026-0508', 'CUSTOMS'),
    ('MID-2026-0515', 'RELEASED'),
    ('MID-2026-0523', 'DELIVERED')
  ) AS s(container_number, status)
 WHERE c.container_number = s.container_number
   AND c.status <> s.status;
