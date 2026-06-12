-- Phase 2.2 Session 1: Row-Level Security + dedicated application DB role.
--
-- RLS is the second, independent isolation layer (defence in depth): Hibernate @TenantId
-- filters JPQL/criteria queries in the app, RLS catches everything Hibernate cannot see —
-- native SQL (analytics!), ad-hoc tooling, future bugs. The same lesson as
-- @SQLRestriction vs native queries, except a miss here would leak another client's data.
--
-- ⚠️ RLS does NOT apply to superusers or roles with BYPASSRLS, and by default not to the
-- table owner. Flyway keeps running as the admin user (DDL); the application must connect
-- as the non-privileged role below or every policy in this file is decorative.
--
-- Session GUCs set by the app on connection checkout (TenantAwareDataSource):
--   app.tenant_id — current tenant for web requests / pipeline processing
--   app.is_system — 'true' for cross-tenant background work (schedulers, outbox relay)
-- Missing/empty GUC → NULLIF(...) yields NULL → policy is false → no rows (fail closed).

DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'smartfreight_app') THEN
        CREATE ROLE smartfreight_app LOGIN PASSWORD '${app_db_password}'
            NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
    END IF;
END $$;

GRANT USAGE ON SCHEMA public TO smartfreight_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO smartfreight_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO smartfreight_app;
-- Future migrations (run by the admin role) keep granting automatically.
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO smartfreight_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO smartfreight_app;

-- Tenant-scoped tables. NOT app_user (login bootstrap — no tenant context exists at
-- authentication time) and NOT app_setting (stays global until S2's tenant_setting).
DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['email_ticket', 'ticket_audit_log', 'carrier_rate', 'outbox_event']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        -- FORCE: policies bind even the table owner (matters if ownership ever moves
        -- to a non-superuser role).
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($p$
            CREATE POLICY tenant_isolation ON %I
                USING (
                    current_setting('app.is_system', true) = 'true'
                    OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint
                )
                WITH CHECK (
                    current_setting('app.is_system', true) = 'true'
                    OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint
                )
        $p$, t);
    END LOOP;
END $$;
