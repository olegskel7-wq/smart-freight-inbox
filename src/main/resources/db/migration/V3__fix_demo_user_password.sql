-- Fix: Re-seed the demo user with correct BCrypt hash
DELETE FROM app_user WHERE email = 'operator@smartfreight.com';

-- Insert with a fresh, validated BCrypt hash for "demo123"
INSERT INTO app_user (email, password, role) VALUES
    ('operator@smartfreight.com',
     '$2a$10$JqLPzYz1yJ2CqH3wdQFTyO/Z7lPxzZ1EQpF.6q/B4K8yY5.Uom.xG',
     'OPERATOR');
