-- =====================================================================
-- ChatBI schema: operational demo data, semantic views and platform tables
-- =====================================================================
CREATE EXTENSION IF NOT EXISTS vector;

-- ---------------------------------------------------------------------
-- 1. Demo ERP / EAM source tables
-- ---------------------------------------------------------------------
CREATE TABLE department (
    id          BIGINT PRIMARY KEY,
    code        VARCHAR(32)  NOT NULL UNIQUE,
    name        VARCHAR(64)  NOT NULL,
    region      VARCHAR(32)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE warehouse (
    id             BIGINT PRIMARY KEY,
    code           VARCHAR(32) NOT NULL UNIQUE,
    name           VARCHAR(64) NOT NULL,
    department_id  BIGINT NOT NULL REFERENCES department(id),
    region         VARCHAR(32) NOT NULL,
    location       VARCHAR(128)
);

CREATE TABLE customer (
    id           BIGINT PRIMARY KEY,
    code         VARCHAR(32)  NOT NULL UNIQUE,
    name         VARCHAR(128) NOT NULL,
    industry     VARCHAR(32)  NOT NULL,
    region       VARCHAR(32)  NOT NULL,
    level        VARCHAR(8)   NOT NULL,
    credit_limit NUMERIC(16, 2) NOT NULL DEFAULT 0,
    status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE product (
    id            BIGINT PRIMARY KEY,
    code          VARCHAR(32)  NOT NULL UNIQUE,
    name          VARCHAR(128) NOT NULL,
    category      VARCHAR(32)  NOT NULL,
    unit          VARCHAR(16)  NOT NULL,
    standard_cost NUMERIC(14, 2) NOT NULL,
    list_price    NUMERIC(14, 2) NOT NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE'
);

CREATE TABLE supplier (
    id         BIGINT PRIMARY KEY,
    code       VARCHAR(32)  NOT NULL UNIQUE,
    name       VARCHAR(128) NOT NULL,
    category   VARCHAR(32)  NOT NULL,
    rating     CHAR(1)      NOT NULL,
    status     VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE'
);

CREATE TABLE equipment (
    id             BIGINT PRIMARY KEY,
    code           VARCHAR(32)  NOT NULL UNIQUE,
    name           VARCHAR(128) NOT NULL,
    category       VARCHAR(32)  NOT NULL,
    warehouse_id   BIGINT NOT NULL REFERENCES warehouse(id),
    purchase_date  DATE NOT NULL,
    status         VARCHAR(16) NOT NULL,
    criticality    VARCHAR(8)  NOT NULL,
    original_value NUMERIC(16, 2) NOT NULL
);

CREATE TABLE sales_order (
    id            BIGINT PRIMARY KEY,
    order_no      VARCHAR(32) NOT NULL UNIQUE,
    customer_id   BIGINT NOT NULL REFERENCES customer(id),
    order_date    DATE NOT NULL,
    status        VARCHAR(16) NOT NULL,
    total_amount  NUMERIC(16, 2) NOT NULL DEFAULT 0,
    currency      CHAR(3) NOT NULL DEFAULT 'CNY',
    salesperson   VARCHAR(32) NOT NULL,
    department_id BIGINT NOT NULL REFERENCES department(id)
);

CREATE TABLE sales_order_item (
    id         BIGSERIAL PRIMARY KEY,
    order_id   BIGINT NOT NULL REFERENCES sales_order(id) ON DELETE CASCADE,
    product_id BIGINT NOT NULL REFERENCES product(id),
    quantity   INTEGER NOT NULL,
    unit_price NUMERIC(14, 2) NOT NULL,
    amount     NUMERIC(16, 2) NOT NULL
);

CREATE TABLE purchase_order (
    id            BIGINT PRIMARY KEY,
    po_no         VARCHAR(32) NOT NULL UNIQUE,
    supplier_id   BIGINT NOT NULL REFERENCES supplier(id),
    order_date    DATE NOT NULL,
    status        VARCHAR(16) NOT NULL,
    total_amount  NUMERIC(16, 2) NOT NULL DEFAULT 0,
    department_id BIGINT NOT NULL REFERENCES department(id)
);

CREATE TABLE purchase_order_item (
    id           BIGSERIAL PRIMARY KEY,
    po_id        BIGINT NOT NULL REFERENCES purchase_order(id) ON DELETE CASCADE,
    product_id   BIGINT NOT NULL REFERENCES product(id),
    quantity     INTEGER NOT NULL,
    unit_price   NUMERIC(14, 2) NOT NULL,
    amount       NUMERIC(16, 2) NOT NULL,
    received_qty INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE inventory (
    id           BIGSERIAL PRIMARY KEY,
    warehouse_id BIGINT NOT NULL REFERENCES warehouse(id),
    product_id   BIGINT NOT NULL REFERENCES product(id),
    quantity     INTEGER NOT NULL,
    safety_stock INTEGER NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (warehouse_id, product_id)
);

CREATE TABLE maintenance_order (
    id              BIGINT PRIMARY KEY,
    order_no        VARCHAR(32) NOT NULL UNIQUE,
    equipment_id    BIGINT NOT NULL REFERENCES equipment(id),
    maintenance_type VARCHAR(32) NOT NULL,
    planned_date    DATE NOT NULL,
    completed_date  DATE,
    status          VARCHAR(16) NOT NULL,
    cost            NUMERIC(14, 2) NOT NULL DEFAULT 0,
    downtime_hours  NUMERIC(8, 1) NOT NULL DEFAULT 0,
    technician      VARCHAR(32) NOT NULL
);

CREATE TABLE ar_invoice (
    id           BIGINT PRIMARY KEY,
    invoice_no   VARCHAR(32) NOT NULL UNIQUE,
    customer_id  BIGINT NOT NULL REFERENCES customer(id),
    invoice_date DATE NOT NULL,
    due_date     DATE NOT NULL,
    amount       NUMERIC(16, 2) NOT NULL,
    paid_amount  NUMERIC(16, 2) NOT NULL DEFAULT 0,
    balance      NUMERIC(16, 2) GENERATED ALWAYS AS (amount - paid_amount) STORED,
    status       VARCHAR(16) NOT NULL
);

CREATE INDEX idx_sales_order_date ON sales_order(order_date);
CREATE INDEX idx_sales_order_customer ON sales_order(customer_id);
CREATE INDEX idx_sales_order_department ON sales_order(department_id);
CREATE INDEX idx_sales_item_order ON sales_order_item(order_id);
CREATE INDEX idx_sales_item_product ON sales_order_item(product_id);
CREATE INDEX idx_purchase_order_date ON purchase_order(order_date);
CREATE INDEX idx_purchase_order_department ON purchase_order(department_id);
CREATE INDEX idx_purchase_item_po ON purchase_order_item(po_id);
CREATE INDEX idx_inventory_product ON inventory(product_id);
CREATE INDEX idx_maintenance_equipment ON maintenance_order(equipment_id);
CREATE INDEX idx_maintenance_date ON maintenance_order(planned_date);
CREATE INDEX idx_ar_invoice_date ON ar_invoice(invoice_date);
CREATE INDEX idx_ar_invoice_customer ON ar_invoice(customer_id);

-- ---------------------------------------------------------------------
-- 2. Curated semantic views. The NL2SQL layer only ever sees these views.
-- ---------------------------------------------------------------------
CREATE VIEW v_sales_fact AS
SELECT
    so.id                AS order_id,
    so.order_no,
    so.order_date,
    to_char(so.order_date, 'YYYY-MM') AS order_month,
    so.status,
    so.salesperson,
    so.department_id,
    d.name               AS department_name,
    d.region,
    so.customer_id,
    c.name               AS customer_name,
    c.industry,
    c.level              AS customer_level,
    soi.product_id,
    p.code               AS product_code,
    p.name               AS product_name,
    p.category,
    soi.quantity,
    soi.unit_price,
    soi.amount,
    ROUND(soi.quantity * p.standard_cost, 2) AS cost_amount,
    ROUND(soi.amount - soi.quantity * p.standard_cost, 2) AS gross_profit,
    so.currency
FROM sales_order so
JOIN sales_order_item soi ON soi.order_id = so.id
JOIN customer c ON c.id = so.customer_id
JOIN product p ON p.id = soi.product_id
JOIN department d ON d.id = so.department_id;

CREATE VIEW v_purchase_fact AS
SELECT
    po.id                AS po_id,
    po.po_no,
    po.order_date,
    to_char(po.order_date, 'YYYY-MM') AS order_month,
    po.status,
    po.department_id,
    d.name               AS department_name,
    d.region,
    po.supplier_id,
    s.name               AS supplier_name,
    s.category           AS supplier_category,
    s.rating             AS supplier_rating,
    poi.product_id,
    p.code               AS product_code,
    p.name               AS product_name,
    p.category,
    poi.quantity,
    poi.unit_price,
    poi.amount,
    poi.received_qty
FROM purchase_order po
JOIN purchase_order_item poi ON poi.po_id = po.id
JOIN supplier s ON s.id = po.supplier_id
JOIN product p ON p.id = poi.product_id
JOIN department d ON d.id = po.department_id;

CREATE VIEW v_inventory_snapshot AS
SELECT
    i.id,
    i.warehouse_id,
    w.name               AS warehouse_name,
    w.region,
    i.product_id,
    p.code               AS product_code,
    p.name               AS product_name,
    p.category,
    i.quantity,
    i.safety_stock,
    CASE
        WHEN i.quantity = 0 THEN 'OUT_OF_STOCK'
        WHEN i.quantity < i.safety_stock THEN 'LOW'
        ELSE 'NORMAL'
    END                  AS stock_status,
    ROUND(i.quantity * p.standard_cost, 2) AS inventory_value,
    i.updated_at
FROM inventory i
JOIN warehouse w ON w.id = i.warehouse_id
JOIN product p ON p.id = i.product_id;

CREATE VIEW v_maintenance_fact AS
SELECT
    mo.id                AS maintenance_id,
    mo.order_no,
    mo.equipment_id,
    e.name               AS equipment_name,
    e.category           AS equipment_category,
    e.criticality,
    e.warehouse_id,
    w.name               AS warehouse_name,
    w.region,
    mo.maintenance_type,
    mo.planned_date,
    to_char(mo.planned_date, 'YYYY-MM') AS planned_month,
    mo.completed_date,
    mo.status,
    mo.cost,
    mo.downtime_hours,
    mo.technician
FROM maintenance_order mo
JOIN equipment e ON e.id = mo.equipment_id
JOIN warehouse w ON w.id = e.warehouse_id;

CREATE VIEW v_receivable_fact AS
SELECT
    ai.id                AS invoice_id,
    ai.invoice_no,
    ai.customer_id,
    c.name               AS customer_name,
    c.industry,
    c.region,
    ai.invoice_date,
    to_char(ai.invoice_date, 'YYYY-MM') AS invoice_month,
    ai.due_date,
    ai.amount,
    ai.paid_amount,
    ai.balance,
    ai.status,
    CASE WHEN ai.balance > 0 AND ai.due_date < CURRENT_DATE
         THEN CURRENT_DATE - ai.due_date ELSE 0 END AS overdue_days
FROM ar_invoice ai
JOIN customer c ON c.id = ai.customer_id;

CREATE VIEW v_equipment_dim AS
SELECT
    e.id                 AS equipment_id,
    e.code               AS equipment_code,
    e.name               AS equipment_name,
    e.category,
    e.warehouse_id,
    w.name               AS warehouse_name,
    w.region,
    d.name               AS department_name,
    e.purchase_date,
    EXTRACT(YEAR FROM e.purchase_date)::int AS purchase_year,
    e.status,
    e.criticality,
    e.original_value
FROM equipment e
JOIN warehouse w ON w.id = e.warehouse_id
JOIN department d ON d.id = w.department_id;

-- ---------------------------------------------------------------------
-- 3. Semantic layer metadata
-- ---------------------------------------------------------------------
CREATE TABLE sem_table (
    id            BIGSERIAL PRIMARY KEY,
    table_name    VARCHAR(64)  NOT NULL UNIQUE,
    business_name VARCHAR(64)  NOT NULL,
    domain        VARCHAR(32)  NOT NULL,
    description   TEXT         NOT NULL,
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE sem_column (
    id            BIGSERIAL PRIMARY KEY,
    table_id      BIGINT       NOT NULL REFERENCES sem_table(id) ON DELETE CASCADE,
    column_name   VARCHAR(64)  NOT NULL,
    business_name VARCHAR(64)  NOT NULL,
    data_type     VARCHAR(32)  NOT NULL,
    description   TEXT,
    unit          VARCHAR(16),
    is_dimension  BOOLEAN      NOT NULL DEFAULT FALSE,
    is_measure    BOOLEAN      NOT NULL DEFAULT FALSE,
    sensitive     BOOLEAN      NOT NULL DEFAULT FALSE,
    ordinal       INT          NOT NULL DEFAULT 0,
    UNIQUE (table_id, column_name)
);

CREATE TABLE sem_metric (
    id            BIGSERIAL PRIMARY KEY,
    metric_code   VARCHAR(64)  NOT NULL UNIQUE,
    metric_name   VARCHAR(64)  NOT NULL,
    description   TEXT         NOT NULL,
    expression    TEXT         NOT NULL,
    base_table    VARCHAR(64)  NOT NULL,
    time_column   VARCHAR(64),
    unit          VARCHAR(16),
    value_format  VARCHAR(16)  NOT NULL DEFAULT 'number',
    synonyms_text TEXT         NOT NULL DEFAULT '',
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE sem_synonym (
    id          BIGSERIAL PRIMARY KEY,
    target_type VARCHAR(16) NOT NULL,
    target_key  VARCHAR(128) NOT NULL,
    synonym     VARCHAR(128) NOT NULL,
    weight      NUMERIC(4, 2) NOT NULL DEFAULT 1.0,
    UNIQUE (target_type, target_key, synonym)
);

CREATE TABLE sem_enum (
    id          BIGSERIAL PRIMARY KEY,
    table_name  VARCHAR(64) NOT NULL,
    column_name VARCHAR(64) NOT NULL,
    enum_value  VARCHAR(64) NOT NULL,
    enum_label  VARCHAR(64) NOT NULL,
    description TEXT,
    UNIQUE (table_name, column_name, enum_value)
);

CREATE TABLE few_shot_example (
    id         BIGSERIAL PRIMARY KEY,
    question   TEXT NOT NULL,
    sql_text   TEXT NOT NULL,
    tables_used VARCHAR(256) NOT NULL,
    tags       VARCHAR(128) NOT NULL DEFAULT '',
    enabled    BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE row_permission (
    id             BIGSERIAL PRIMARY KEY,
    user_id        VARCHAR(64) NOT NULL,
    table_name     VARCHAR(64) NOT NULL,
    column_name    VARCHAR(64) NOT NULL,
    allowed_values TEXT NOT NULL,
    description    VARCHAR(128),
    enabled        BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE INDEX idx_row_permission_user ON row_permission(user_id, table_name);

CREATE TABLE semantic_embedding (
    id            BIGSERIAL PRIMARY KEY,
    owner_type    VARCHAR(16) NOT NULL,
    owner_key     VARCHAR(128) NOT NULL,
    content       TEXT NOT NULL,
    content_hash  VARCHAR(64) NOT NULL,
    metadata      JSONB NOT NULL DEFAULT '{}'::jsonb,
    embedding     vector(512) NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (owner_type, owner_key)
);
CREATE INDEX idx_semantic_embedding_hnsw
    ON semantic_embedding USING hnsw (embedding vector_cosine_ops);

CREATE TABLE app_user (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(64) NOT NULL UNIQUE,
    display_name  VARCHAR(64) NOT NULL,
    role          VARCHAR(64) NOT NULL,
    department_id BIGINT REFERENCES department(id),
    is_admin      BOOLEAN NOT NULL DEFAULT FALSE,
    enabled       BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE conversation (
    id         UUID PRIMARY KEY,
    user_id    VARCHAR(64) NOT NULL,
    title      VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_conversation_user ON conversation(user_id, updated_at DESC);

CREATE TABLE conversation_message (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
    role            VARCHAR(16) NOT NULL,
    content         TEXT NOT NULL,
    sql_text        TEXT,
    payload         JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_message_conversation ON conversation_message(conversation_id, id);

CREATE TABLE query_audit (
    id                BIGSERIAL PRIMARY KEY,
    request_id        VARCHAR(64) NOT NULL,
    conversation_id   UUID,
    user_id           VARCHAR(64) NOT NULL,
    question          TEXT NOT NULL,
    generated_sql     TEXT,
    final_sql         TEXT,
    generated_by      VARCHAR(16),
    status            VARCHAR(24) NOT NULL,
    stage             VARCHAR(32),
    error_message     TEXT,
    attempts          INT NOT NULL DEFAULT 0,
    row_count         INT NOT NULL DEFAULT 0,
    elapsed_ms        BIGINT NOT NULL DEFAULT 0,
    prompt_tokens     INT NOT NULL DEFAULT 0,
    completion_tokens INT NOT NULL DEFAULT 0,
    cost_usd          NUMERIC(12, 6) NOT NULL DEFAULT 0,
    schema_hits       JSONB,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_query_audit_created ON query_audit(created_at DESC);
CREATE INDEX idx_query_audit_user ON query_audit(user_id, created_at DESC);

CREATE TABLE query_feedback (
    id         BIGSERIAL PRIMARY KEY,
    audit_id   BIGINT REFERENCES query_audit(id) ON DELETE CASCADE,
    user_id    VARCHAR(64) NOT NULL,
    rating     SMALLINT NOT NULL,
    comment    VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
