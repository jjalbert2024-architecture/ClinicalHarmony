-- =============================================================================
-- 22_api_gateway_readonly_role.sql — Phase 7 (api-gateway)
--
-- The api-gateway serves the dashboard's read models with plain SQL, but must never write:
-- every change goes through the service that owns it (GoldWriter stays the only Gold writer).
-- So it logs in as its own least-privilege role — SELECT on the five data schemas, nothing else,
-- and every session read-only by default — instead of the owner role the other services use.
--
-- DEV-ONLY password, same as every other credential in this repo's local stack.
-- Idempotent: safe to re-run against the live container.
-- =============================================================================

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'clinicalharmony_gateway_ro') THEN
        CREATE ROLE clinicalharmony_gateway_ro LOGIN PASSWORD 'gateway-ro-dev-password';
    END IF;
END $$;

ALTER ROLE clinicalharmony_gateway_ro SET default_transaction_read_only = on;

GRANT CONNECT ON DATABASE clinicalharmony TO clinicalharmony_gateway_ro;
GRANT USAGE ON SCHEMA bronze, silver, gold, ontology, pmi TO clinicalharmony_gateway_ro;
GRANT SELECT ON ALL TABLES IN SCHEMA bronze, silver, gold, ontology, pmi TO clinicalharmony_gateway_ro;

-- Tables added by later migrations (run as the owner role) are readable too.
ALTER DEFAULT PRIVILEGES FOR ROLE clinicalharmony IN SCHEMA bronze, silver, gold, ontology, pmi
    GRANT SELECT ON TABLES TO clinicalharmony_gateway_ro;

COMMENT ON ROLE clinicalharmony_gateway_ro IS
    'api-gateway (Phase 7): SELECT-only on bronze/silver/gold/ontology/pmi, sessions read-only by default. Never writes.';
