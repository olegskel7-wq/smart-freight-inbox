CREATE TABLE app_setting (
    key   VARCHAR(100) PRIMARY KEY,
    value VARCHAR(500) NOT NULL
);

INSERT INTO app_setting (key, value) VALUES
    ('notification.draft-enabled', 'true');
