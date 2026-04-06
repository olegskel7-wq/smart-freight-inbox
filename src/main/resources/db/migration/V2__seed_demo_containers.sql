-- Демонстрационные данные для тестирования пайплайна.
-- Контейнеры в реальном порту Felixstowe (форматы номеров ISO 6346: 4 буквы + 7 цифр).

INSERT INTO container (container_number, status, eta, location) VALUES
    ('MSCU1234567', 'IN_TRANSIT',  '2026-04-20 08:00:00', 'Vessel MSC Francesca, Bay 14'),
    ('HLXU8764321', 'AT_PORT',     '2026-04-10 14:00:00', 'Felixstowe Terminal 2, Row D'),
    ('CMAU5551234', 'CUSTOMS',     '2026-04-12 09:30:00', 'HMRC Examination Bay 3'),
    ('OOLU9876543', 'DELIVERED',   '2026-04-05 11:00:00', 'DHL Depot, Birmingham'),
    ('EVRU3219876', 'IN_TRANSIT',  '2026-04-25 16:00:00', 'Vessel Ever Gentle, ETA Felixstowe'),
    ('TEMU4448888', 'AT_PORT',     '2026-04-08 07:00:00', 'Felixstowe Terminal 1, Row B'),
    ('CAIU7773330', 'RELEASED',    '2026-04-07 10:00:00', 'Awaiting haulier collection');

-- Демо-пользователь для тестирования JWT.
-- Пароль: "demo123" захешированный BCrypt (стоимость 10).
-- Для генерации нового хеша: new BCryptPasswordEncoder().encode("your_password")
INSERT INTO app_user (email, password, role) VALUES
    ('operator@smartfreight.com',
     '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVEFDa',
     'OPERATOR');
