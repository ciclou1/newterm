-- ---------------------------------------------------------------------
-- Production authentication: password credentials and explicit RBAC roles.
-- The original demo schema only carried a display role plus is_admin, so the
-- columns are added defensively for databases that were created before this
-- migration. Existing demo accounts keep working through is_admin, and the
-- explicit role column becomes the source of truth going forward.
-- ---------------------------------------------------------------------

ALTER TABLE app_user ADD COLUMN IF NOT EXISTS password_hash VARCHAR(100);
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS roles VARCHAR(64) NOT NULL DEFAULT 'VIEWER';
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS last_login_at TIMESTAMPTZ;

UPDATE app_user SET roles = 'ADMIN', is_admin = TRUE
WHERE username = 'lisi';

UPDATE app_user SET roles = 'MANAGER', is_admin = FALSE
WHERE username IN ('zhangsan', 'wangwu');

-- Demo password for all three accounts: ChatBI@2026
-- Change or remove these credentials before any shared deployment.
UPDATE app_user
SET password_hash = '$2b$10$Pv39pSg/8XCPhNLUont1r.E49VjyRU/lnIyj5bBJXYBtvgkp99pTy'
WHERE username = 'lisi';

UPDATE app_user
SET password_hash = '$2b$10$dqHE3oy7Sa6uvyrj7rgRlOMim0XzbrC8UTbFvR/WfTB2WgaKWwiAe'
WHERE username = 'zhangsan';

UPDATE app_user
SET password_hash = '$2b$10$VqJXzra3Yfyz1XR7Vw5m4.7AkO5lzZoK1h7xnt66g3nb92RxRkFuO'
WHERE username = 'wangwu';

-- Dedicated principal for the stdio MCP server. It has no password (cannot
-- log in through /api/auth/login) and no ADMIN role. It still needs a row
-- scope for v_sales_fact because a protected table with no rule is denied.
INSERT INTO app_user (username, display_name, role, department_id, is_admin, enabled, roles)
VALUES ('mcp-service', 'MCP 服务账号', '服务账号', NULL, FALSE, TRUE, 'VIEWER')
ON CONFLICT (username) DO UPDATE
SET display_name = EXCLUDED.display_name,
    role = EXCLUDED.role,
    is_admin = FALSE,
    enabled = TRUE,
    roles = 'VIEWER',
    password_hash = NULL;

INSERT INTO row_permission (user_id, table_name, column_name, allowed_values, description)
SELECT 'mcp-service', 'v_sales_fact', 'department_id', '1,2,3,4', 'MCP 服务账号的只读销售数据范围'
WHERE NOT EXISTS (
    SELECT 1 FROM row_permission
    WHERE user_id = 'mcp-service' AND table_name = 'v_sales_fact'
      AND column_name = 'department_id'
);
