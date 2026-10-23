-- Deterministic demo dataset. 2024-01-01 .. 2026-10, designed for repeatable
-- NL2SQL evaluation (no random() calls).

INSERT INTO department (id, code, name, region) VALUES
    (1, 'D-EE',  '华东销售部', '华东'),
    (2, 'D-SE',  '华南销售部', '华南'),
    (3, 'D-NE',  '华北销售部', '华北'),
    (4, 'D-SW',  '西南销售部', '西南'),
    (5, 'D-PUR', '采购部',     '全国'),
    (6, 'D-EAM', '设备管理部', '全国'),
    (7, 'D-FIN', '财务部',     '全国');

INSERT INTO warehouse (id, code, name, department_id, region, location) VALUES
    (1, 'WH-SH', '上海中心仓', 6, '华东', '上海市青浦区'),
    (2, 'WH-GZ', '广州中心仓', 6, '华南', '广州市黄埔区'),
    (3, 'WH-BJ', '北京中心仓', 6, '华北', '北京市顺义区'),
    (4, 'WH-CD', '成都中心仓', 6, '西南', '成都市双流区'),
    (5, 'WH-SZ', '苏州备件仓', 6, '华东', '苏州市吴中区');

INSERT INTO customer (id, code, name, industry, region, level, credit_limit, status)
SELECT
    g,
    'C-' || lpad(g::text, 3, '0'),
    (ARRAY['远航','启明','鼎盛','恒达','新锐','嘉禾','中科','北辰'])[1 + ((g - 1) % 8)]
        || (ARRAY['科技','制造','能源','医药','电子'])[1 + ((g - 1) / 8)]
        || '有限公司',
    (ARRAY['制造业','能源','医药','电子','汽车'])[1 + ((g - 1) / 8)],
    (ARRAY['华东','华南','华北','西南'])[1 + ((g * 3) % 4)],
    (ARRAY['A','B','C'])[1 + (g % 3)],
    500000 + g * 120000,
    CASE WHEN g % 19 = 0 THEN 'INACTIVE' ELSE 'ACTIVE' END
FROM generate_series(1, 40) AS g;

INSERT INTO product (id, code, name, category, unit, standard_cost, list_price, status)
SELECT
    g,
    'P-' || lpad(g::text, 3, '0'),
    (ARRAY['工业传感器','液压设备','电气元件','备品备件','仪器仪表'])[1 + ((g - 1) % 5)]
        || ' ' || (ARRAY['X','H','E','S','M'])[1 + ((g - 1) % 5)]
        || '-' || lpad(g::text, 3, '0'),
    (ARRAY['工业传感器','液压设备','电气元件','备品备件','仪器仪表'])[1 + ((g - 1) % 5)],
    (ARRAY['件','套','台'])[1 + (g % 3)],
    ROUND(200 + (g * 173) % 8000 + (g % 10) * 13.5, 2),
    ROUND((200 + (g * 173) % 8000) * (1.35 + (g % 7) * 0.03) + (g % 10) * 18.8, 2),
    CASE WHEN g % 17 = 0 THEN 'INACTIVE' ELSE 'ACTIVE' END
FROM generate_series(1, 50) AS g;

INSERT INTO supplier (id, code, name, category, rating, status)
SELECT
    g,
    'S-' || lpad(g::text, 3, '0'),
    (ARRAY['华源','联创','泰盛','博远'])[1 + ((g - 1) % 4)]
        || (ARRAY['原材料','零部件','服务外包','设备'])[1 + ((g - 1) / 4)]
        || '有限公司',
    (ARRAY['原材料','零部件','服务外包','设备'])[1 + ((g - 1) / 4)],
    (ARRAY['A','B','C'])[1 + (g % 3)],
    CASE WHEN g % 11 = 0 THEN 'INACTIVE' ELSE 'ACTIVE' END
FROM generate_series(1, 16) AS g;

INSERT INTO equipment (id, code, name, category, warehouse_id, purchase_date, status, criticality, original_value)
SELECT
    g,
    'EQ-' || lpad(g::text, 3, '0'),
    (ARRAY['数控机床','注塑机','空压机','检测设备','搬运设备'])[1 + ((g - 1) % 5)]
        || '-' || lpad(g::text, 3, '0'),
    (ARRAY['数控机床','注塑机','空压机','检测设备','搬运设备'])[1 + ((g - 1) % 5)],
    1 + ((g - 1) % 5),
    DATE '2019-03-01' + ((g * 47) % 2100),
    (ARRAY['RUNNING','RUNNING','RUNNING','IDLE','MAINTENANCE','SCRAPPED'])[1 + (g % 6)],
    (ARRAY['HIGH','MEDIUM','LOW'])[1 + (g % 3)],
    120000 + (g * 17831) % 1800000
FROM generate_series(1, 40) AS g;

INSERT INTO sales_order (id, order_no, customer_id, order_date, status, total_amount, currency, salesperson, department_id)
SELECT
    g,
    'SO' || to_char(DATE '2024-01-01' + ((g * 37) % 1020), 'YYYYMMDD') || '-' || lpad(g::text, 4, '0'),
    1 + ((g * 7) % 40),
    DATE '2024-01-01' + ((g * 37) % 1020),
    CASE WHEN g % 23 = 0 THEN 'CANCELLED' WHEN g % 13 = 0 THEN 'PENDING' ELSE 'COMPLETED' END,
    0,
    'CNY',
    (ARRAY['张伟','李娜','王强','陈晨'])[1 + ((g - 1) % 4)],
    1 + ((g - 1) % 4)
FROM generate_series(1, 1200) AS g;

INSERT INTO sales_order_item (order_id, product_id, quantity, unit_price, amount)
SELECT
    so.id,
    p.id,
    1 + ((so.id + n.item_no * 3) % 20),
    ROUND(p.list_price * (0.88 + ((so.id * 3 + n.item_no * 7) % 20) / 100.0), 2),
    ROUND(
        (1 + ((so.id + n.item_no * 3) % 20))
        * ROUND(p.list_price * (0.88 + ((so.id * 3 + n.item_no * 7) % 20) / 100.0), 2),
        2
    )
FROM sales_order so
CROSS JOIN LATERAL generate_series(1, 1 + (so.id % 4)) AS n(item_no)
JOIN product p ON p.id = 1 + ((so.id * 7 + n.item_no * 13) % 50);

UPDATE sales_order so
SET total_amount = totals.total
FROM (SELECT order_id, SUM(amount) AS total FROM sales_order_item GROUP BY order_id) totals
WHERE totals.order_id = so.id;

INSERT INTO purchase_order (id, po_no, supplier_id, order_date, status, total_amount, department_id)
SELECT
    g,
    'PO' || to_char(DATE '2024-01-01' + ((g * 43) % 1020), 'YYYYMMDD') || '-' || lpad(g::text, 4, '0'),
    1 + ((g * 5) % 16),
    DATE '2024-01-01' + ((g * 43) % 1020),
    (ARRAY['RECEIVED','RECEIVED','APPROVED','DRAFT','CANCELLED'])[1 + (g % 5)],
    0,
    1 + ((g - 1) % 4)
FROM generate_series(1, 500) AS g;

INSERT INTO purchase_order_item (po_id, product_id, quantity, unit_price, amount, received_qty)
SELECT
    po.id,
    p.id,
    5 + ((po.id + n.item_no * 11) % 96),
    ROUND(p.standard_cost * (0.92 + ((po.id + n.item_no) % 9) / 100.0), 2),
    ROUND(
        (5 + ((po.id + n.item_no * 11) % 96))
        * ROUND(p.standard_cost * (0.92 + ((po.id + n.item_no) % 9) / 100.0), 2),
        2
    ),
    CASE WHEN po.status = 'RECEIVED' THEN 5 + ((po.id + n.item_no * 11) % 96) ELSE 0 END
FROM purchase_order po
CROSS JOIN LATERAL generate_series(1, 1 + (po.id % 3)) AS n(item_no)
JOIN product p ON p.id = 1 + ((po.id * 11 + n.item_no * 17) % 50);

UPDATE purchase_order po
SET total_amount = totals.total
FROM (SELECT po_id, SUM(amount) AS total FROM purchase_order_item GROUP BY po_id) totals
WHERE totals.po_id = po.id;

INSERT INTO inventory (warehouse_id, product_id, quantity, safety_stock, updated_at)
SELECT
    w,
    p,
    (w * 37 + p * 53) % 120,
    10 + (p % 20),
    TIMESTAMPTZ '2026-10-20 08:00:00+08'
FROM generate_series(1, 5) AS w
CROSS JOIN generate_series(1, 50) AS p;

INSERT INTO maintenance_order
    (id, order_no, equipment_id, maintenance_type, planned_date, completed_date, status, cost, downtime_hours, technician)
SELECT
    g,
    'MO' || to_char(DATE '2024-01-05' + ((g * 29) % 1000), 'YYYYMMDD') || '-' || lpad(g::text, 4, '0'),
    1 + ((g * 7) % 40),
    (ARRAY['预防性维护','故障维修','巡检','校准'])[1 + (g % 4)],
    DATE '2024-01-05' + ((g * 29) % 1000),
    CASE WHEN g % 10 <> 0 AND g % 17 <> 0
         THEN DATE '2024-01-05' + ((g * 29) % 1000) + (1 + g % 5)
         ELSE NULL END,
    CASE WHEN g % 10 = 0 THEN 'IN_PROGRESS'
         WHEN g % 17 = 0 THEN 'PLANNED'
         ELSE 'COMPLETED' END,
    800 + (g * 137) % 45000,
    ROUND(((g * 17) % 480) / 10.0, 1),
    (ARRAY['赵磊','钱峰','孙杰','周凯','吴敏'])[1 + (g % 5)]
FROM generate_series(1, 600) AS g;

INSERT INTO ar_invoice (id, invoice_no, customer_id, invoice_date, due_date, amount, paid_amount, status)
SELECT
    so.id,
    'INV' || to_char(so.order_date, 'YYYYMMDD') || '-' || lpad(so.id::text, 4, '0'),
    so.customer_id,
    so.order_date,
    so.order_date + 30,
    so.total_amount,
    CASE
        WHEN so.id % 7 = 0 THEN so.total_amount
        WHEN so.id % 7 IN (1, 2) THEN ROUND(so.total_amount * 0.4, 2)
        ELSE 0
    END,
    CASE
        WHEN so.id % 7 = 0 THEN 'PAID'
        WHEN so.order_date + 30 < DATE '2026-10-23' THEN 'OVERDUE'
        WHEN so.id % 7 IN (1, 2) THEN 'PARTIAL'
        ELSE 'OPEN'
    END
FROM sales_order so
WHERE so.status = 'COMPLETED' AND so.id <= 900;

ANALYZE;
