-- Flyway callback: runs after every successful migrate as the schema owner.
-- Grant the read-only execution role SELECT on the semantic views only.
-- Keep this list in sync with the v_* views exposed as the analytical API.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'chatbi_ro') THEN
        EXECUTE 'REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA public FROM chatbi_ro';
        EXECUTE 'GRANT SELECT ON public.v_sales_fact, public.v_purchase_fact, '
             || 'public.v_inventory_snapshot, public.v_maintenance_fact, '
             || 'public.v_receivable_fact, public.v_equipment_dim TO chatbi_ro';
    END IF;
END $$;
