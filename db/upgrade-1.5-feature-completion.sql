-- =====================================================================
-- 升级脚本 V1.5：批次四（功能补齐）数据结构增量
-- 适用：对已有数据库执行一次（全新部署由 db/schema.sql 直接建表，无需执行本脚本）
-- 用法（仓库根目录）：
--   docker compose exec -T mysql mysql -usmms -p"$SMMS_DB_PASSWORD" smms < db/upgrade-1.5-feature-completion.sql
-- 内容：
--   ① 定时任务执行日志 scheduled_task_log（IR-SW-06 / GAP-P1-03）
--   ② 数据备份记录 sys_backup_record（FR-SYS-10 / GAP-P2-23）
--   ③ 新菜单与权限点：定时任务 sys:sched:list/run、数据备份 sys:backup:list/run（仅授予超管）
--   ④ 系统参数：无结束符扫码自动查询 barcode_auto_query（IR-HW-01③ / GAP-P1-13）
--   ⑤ 供应商最近供货价 supplier_goods.last_supply_price（FR-SUP-04 / GAP-P2-13）
--   ⑥ 建议采购原值 purchase_order_item.suggested_qty/suggested_price（UC-INV-002 / GAP-P2-14）
--   ⑦ 17 张业务表补齐审计四字段（DR-C-08 / GAP-P2-28）
--   ⑧ 成本精度 receipt_order_item.received_price、purchase_return_item.return_price 12,2 → 12,4（DR-C-07 / GAP-P2-29）
-- 说明：CREATE TABLE 使用 IF NOT EXISTS、权限使用 INSERT IGNORE 可重复执行；
--       ALTER ADD COLUMN 重复执行报 1060（列已存在）可忽略，勿重复执行。
-- =====================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- ① 定时任务执行日志（IR-SW-06）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS scheduled_task_log (
    id             BIGINT UNSIGNED AUTO_INCREMENT COMMENT '日志ID',
    task_code      VARCHAR(50)   NOT NULL COMMENT '任务代码（PROMOTION_EXPIRE/...）',
    task_name      VARCHAR(100)  NOT NULL COMMENT '任务名称',
    trigger_type   VARCHAR(20)   NOT NULL COMMENT '触发方式：SCHEDULED定时/MANUAL手工补跑',
    trigger_by     VARCHAR(50)   DEFAULT NULL COMMENT '手工触发人（定时触发为空）',
    status         VARCHAR(20)   NOT NULL COMMENT '执行结果：SUCCESS成功/FAILED失败',
    started_at     DATETIME      NOT NULL COMMENT '开始时间',
    finished_at    DATETIME      NOT NULL COMMENT '结束时间',
    duration_ms    BIGINT        NOT NULL COMMENT '耗时（毫秒）',
    result_summary VARCHAR(500)  DEFAULT NULL COMMENT '结果摘要（如：处理 3 条）',
    error_message  VARCHAR(500)  DEFAULT NULL COMMENT '失败原因（最多重试 2 次后仍失败）',
    retry_count    INT           NOT NULL DEFAULT 0 COMMENT '失败重试次数',
    create_time    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间',
    PRIMARY KEY (id),
    KEY idx_stl_code_time (task_code, started_at),
    KEY idx_stl_started (started_at)
) ENGINE = InnoDB COMMENT = '定时任务执行日志（IR-SW-06）';

-- ---------------------------------------------------------------------
-- ② 数据备份记录（FR-SYS-10）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_backup_record (
    id            BIGINT UNSIGNED AUTO_INCREMENT COMMENT '记录ID',
    file_name     VARCHAR(200)  NOT NULL COMMENT '备份文件名',
    file_path     VARCHAR(500)  NOT NULL COMMENT '备份文件路径',
    file_size     BIGINT        NOT NULL DEFAULT 0 COMMENT '文件字节数',
    trigger_type  VARCHAR(20)   NOT NULL COMMENT '触发方式：SCHEDULED定时/MANUAL手工一键',
    trigger_by    VARCHAR(50)   DEFAULT NULL COMMENT '手工触发人（定时触发为空）',
    status        VARCHAR(20)   NOT NULL COMMENT '执行结果：SUCCESS成功/FAILED失败',
    started_at    DATETIME      NOT NULL COMMENT '开始时间',
    finished_at   DATETIME      DEFAULT NULL COMMENT '结束时间',
    duration_ms   BIGINT        DEFAULT NULL COMMENT '耗时（毫秒）',
    error_message VARCHAR(500)  DEFAULT NULL COMMENT '失败原因',
    create_time   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间',
    PRIMARY KEY (id),
    KEY idx_sbr_started (started_at),
    KEY idx_sbr_status (status)
) ENGINE = InnoDB COMMENT = '数据备份记录（FR-SYS-10）';

-- ---------------------------------------------------------------------
-- ③ 新菜单与权限点（仅超级管理员；店长等角色如需可在角色管理中授权）
-- ---------------------------------------------------------------------
INSERT IGNORE INTO sys_permission (id, parent_id, name, perm_type, perm_code, route_path, component, icon, sort_no, status, create_by) VALUES
(16, 1, '定时任务', 'M', 'sys:sched:list',  '/system/scheduled-task', 'system/scheduled-task/index', 'AlarmClock', 7, 1, 'system'),
(161, 16, '手工触发任务', 'B', 'sys:sched:run',  NULL, NULL, NULL, 1, 1, 'system'),
(17, 1, '数据备份', 'M', 'sys:backup:list', '/system/backup',   'system/backup/index',  'FolderOpened', 8, 1, 'system'),
(171, 17, '一键触发备份', 'B', 'sys:backup:run',  NULL, NULL, NULL, 1, 1, 'system');

INSERT IGNORE INTO sys_role_permission (role_id, perm_id) VALUES
(1, 16), (1, 161), (1, 17), (1, 171);

ALTER TABLE supplier_goods ADD COLUMN last_supply_price DECIMAL(12,4) DEFAULT NULL COMMENT '最近供货价（最近一次入库实收单价，入库回写，FR-SUP-04）' AFTER last_supply_time;

-- ---------------------------------------------------------------------
-- ⑤ 建议采购原值（UC-INV-002 6a / GAP-P2-14）：草稿保留生成时的建议量与建议价
-- ---------------------------------------------------------------------
ALTER TABLE purchase_order_item ADD COLUMN suggested_qty DECIMAL(10,3) DEFAULT NULL COMMENT '生成草稿时的建议采购量（UC-INV-002 6a；手工建单为空）' AFTER returned_qty;
ALTER TABLE purchase_order_item ADD COLUMN suggested_price DECIMAL(12,2) DEFAULT NULL COMMENT '生成草稿时的建议单价（同上）' AFTER suggested_qty;

-- ---------------------------------------------------------------------
-- ④ 系统参数：无结束符扫码自动查询（IR-HW-01③ / GAP-P1-13）
-- ---------------------------------------------------------------------
INSERT IGNORE INTO sys_config (config_name, config_key, config_value, value_type, config_group, editable, instant_effect, remark, update_by) VALUES
('无结束符扫码自动查询', 'barcode_auto_query', 'true', 'BOOLEAN', 'sale', 1, 1,
 '扫码枪无结束符时按输入间隔≤50ms判定扫码结束并自动查询（IR-HW-01③）', 'system');
