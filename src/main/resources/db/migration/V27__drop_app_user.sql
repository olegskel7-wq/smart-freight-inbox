-- Phase 2.3 S2: retire app_user. Keycloak is now the sole identity store — operators (and
-- their tenant_id attribute + realm roles) live in the realm, provisioned via the Admin REST
-- API (KeycloakAdminService). The login path stopped reading this table in S1 (form login →
-- oauth2Login), and S2 moves provisioning off it too.
--
-- Safe to drop: app_user has only an OUTGOING FK to tenant (V18) — nothing references it.
-- DROP TABLE removes that constraint and the table's grants/policies along with it.
DROP TABLE IF EXISTS app_user;
