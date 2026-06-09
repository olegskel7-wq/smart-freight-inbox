-- Feature-flag scaffolding for plan-based gating.
-- Flags live as `feature.*` rows in app_setting; a missing row defaults to enabled in code
-- (see SettingsService.isFeatureEnabled). Seeding makes the flag discoverable and toggleable.
-- Under multi-tenancy (Phase 2) these move to a per-tenant scope so features can be unlocked
-- by subscription plan without code changes.
INSERT INTO app_setting (key, value) VALUES
    ('feature.attachment-parsing', 'true')
ON CONFLICT (key) DO NOTHING;
