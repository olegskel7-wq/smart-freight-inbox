-- Phase 1.2: carrier rate sheets.
-- Rates are uploaded via CSV on the /rates page and looked up during drafting so the AI
-- can quote a real sell price (rate + margin) instead of writing a price-less draft.
CREATE TABLE carrier_rate (
    id             BIGSERIAL PRIMARY KEY,
    carrier        VARCHAR(120)  NOT NULL,
    origin         VARCHAR(120)  NOT NULL,
    destination    VARCHAR(120)  NOT NULL,
    rate           NUMERIC(12,2) NOT NULL CHECK (rate >= 0),
    currency       VARCHAR(3)    NOT NULL DEFAULT 'GBP',
    margin_percent NUMERIC(5,2)  NOT NULL DEFAULT 0,
    valid_until    DATE,
    updated_at     TIMESTAMP     NOT NULL DEFAULT now()
);

-- One rate per carrier + route; re-uploading a CSV updates the row instead of duplicating it.
CREATE UNIQUE INDEX uq_carrier_rate_route
    ON carrier_rate (lower(carrier), lower(origin), lower(destination));

INSERT INTO app_setting (key, value) VALUES
    ('feature.rate-sheets', 'true')
ON CONFLICT (key) DO NOTHING;
