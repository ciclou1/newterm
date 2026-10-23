-- Region labels are shared by the sales, purchase, maintenance, receivable,
-- inventory and equipment views. V3 seeded them for sales only; the other
-- views need the same enum vocabulary so "华东区" becomes a real WHERE filter
-- instead of being silently dropped.
INSERT INTO sem_enum (table_name, column_name, enum_value, enum_label, description)
SELECT t.table_name, 'region', r.value, r.value, '业务区域'
FROM (VALUES ('v_sales_fact'), ('v_purchase_fact'), ('v_maintenance_fact'),
             ('v_receivable_fact'), ('v_inventory_snapshot'), ('v_equipment_dim')) AS t(table_name)
CROSS JOIN (VALUES ('华东'), ('华南'), ('华北'), ('西南')) AS r(value)
ON CONFLICT (table_name, column_name, enum_value) DO NOTHING;
