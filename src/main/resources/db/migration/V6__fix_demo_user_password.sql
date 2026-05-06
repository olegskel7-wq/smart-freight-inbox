-- Regenerate demo user with a BCrypt hash verified by BCryptPasswordEncoder.matches("demo123", hash)
DELETE FROM app_user WHERE email = 'operator@smartfreight.com';

INSERT INTO app_user (email, password, role) VALUES
    ('operator@smartfreight.com',
     '$2a$10$vDM1KvzVlZ9uQ8CSR.X5k.1qho.9gwoUdOSGIZh2V3LKuXWReWkra',
     'OPERATOR');
