-- The application writes metadata with the owner account, but every generated
-- analytical query runs through this login. It can only SELECT.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'chatbi_ro') THEN
        CREATE ROLE chatbi_ro LOGIN PASSWORD 'chatbi_ro' NOSUPERUSER NOCREATEDB NOCREATEROLE;
    END IF;
END $$;

GRANT CONNECT ON DATABASE chatbi TO chatbi_ro;
GRANT USAGE ON SCHEMA public TO chatbi_ro;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO chatbi_ro;
GRANT SELECT ON ALL SEQUENCES IN SCHEMA public TO chatbi_ro;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO chatbi_ro;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON SEQUENCES TO chatbi_ro;
