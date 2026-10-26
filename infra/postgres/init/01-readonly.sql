-- The application writes metadata with the owner account, but every generated
-- analytical query runs through this login. It can only SELECT, and only from
-- the six semantic views (see the Flyway afterMigrate callback).
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'chatbi_ro') THEN
        CREATE ROLE chatbi_ro LOGIN PASSWORD 'chatbi_ro' NOSUPERUSER NOCREATEDB NOCREATEROLE;
    END IF;
END $$;

-- The database name is whatever POSTGRES_DB created, so do not hardcode it.
DO $$
BEGIN
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO chatbi_ro', current_database());
END $$;
GRANT USAGE ON SCHEMA public TO chatbi_ro;

-- Never grant blanket SELECT. app_user, query_audit, conversation*,
-- semantic_* and the physical ERP tables must remain unreadable even if the
-- AST guard is ever bypassed. The v_* views are granted by
-- backend/src/main/resources/db/migration/afterMigrate.sql, which runs after
-- Flyway has created them (this init script runs before migrations).
REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA public FROM chatbi_ro;
REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public FROM chatbi_ro;
ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE ALL ON TABLES FROM chatbi_ro;
ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE ALL ON SEQUENCES FROM chatbi_ro;
