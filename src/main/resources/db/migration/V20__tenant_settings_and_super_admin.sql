-- Phase 2.2 Session 2: per-tenant settings + platform admin role.
--
-- tenant_setting holds per-tenant overrides for the keys that used to be global in
-- app_setting. Resolution order (SettingsService): tenant_setting (current tenant) →
-- app_setting (platform default) → code default. app_setting itself is untouched — its
-- rows become the platform-wide defaults every tenant inherits until they override.
-- No data is migrated: an absent override IS the correct initial state.

CREATE TABLE tenant_setting (
    id        BIGSERIAL    PRIMARY KEY,
    tenant_id BIGINT       NOT NULL REFERENCES tenant(id),
    key       VARCHAR(100) NOT NULL,
    value     TEXT         NOT NULL,
    UNIQUE (tenant_id, key)
);

-- Same two-layer isolation as the V19 tables: RLS guards native SQL / raw JDBC,
-- Hibernate @TenantId guards entity queries. Grants arrive automatically via the
-- ALTER DEFAULT PRIVILEGES from V19 (this migration runs as the admin role).
ALTER TABLE tenant_setting ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_setting FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant_setting
    USING (
        current_setting('app.is_system', true) = 'true'
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint
    )
    WITH CHECK (
        current_setting('app.is_system', true) = 'true'
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint
    );

-- Platform admin: SUPER_ADMIN manages tenants (/admin/tenants) and keeps full operator
-- access (DbUserDetailsService grants ROLE_OPERATOR alongside). Promote the existing
-- operator of the Default tenant — no-op on databases without the dev-seeded user.
UPDATE app_user SET role = 'SUPER_ADMIN'
    WHERE email = 'operator@smartfreight.com' AND tenant_id = 1;
