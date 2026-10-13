-- =====================================================================
-- 升级脚本 V1.3：批次二（权限与资金安全）数据结构增量
-- 适用：已初始化的存量数据库（全新部署由 db/schema.sql / init-data.sql 直接初始化，无需执行本脚本）
-- 用法：docker compose exec -T mysql mysql -usmms -p"$SMMS_DB_PASSWORD" smms \
--         < db/upgrade-1.3-permission-fund-safety.sql
-- 内容：
--   1) 新增权限点 205"分类树只读"（sys:cat:tree），并授予采购员/库管员：
--      业务页面按分类筛选/选品（库存查询、预警、盘点建单等）不再因缺 sys:cat:list 而 403，
--      同时不暴露分类管理菜单（GAP-P0-05/06 同族的权限链修复）。INSERT IGNORE 可重复执行。
--   2) operation_log 增加 real_name 列（GAP-P1-10：关键动作同时记录账号与真实姓名，BR-SYS-07）；
--      历史日志无法回填真实姓名（仅存账号），保持 NULL。该 ALTER 仅在首次执行成功，
--      重复执行会报 1060"Duplicate column"并终止后续语句——请勿重复执行本脚本；
--      若需重跑，请跳过本 ALTER 或加 --force。
--   3) 黑名单语义修正仅涉及表注释，不改变结构（见 db/schema.sql / 数据库设计说明书）。
-- =====================================================================

INSERT IGNORE INTO sys_permission (id, parent_id, name, perm_type, perm_code, route_path, component, icon, sort_no, status, create_by)
VALUES (205, 20, '分类树只读', 'B', 'sys:cat:tree', NULL, NULL, NULL, 5, 1, 'system');

INSERT IGNORE INTO sys_role_permission (role_id, perm_id) VALUES
(3, 205),
(4, 205);

ALTER TABLE operation_log
    ADD COLUMN real_name VARCHAR(30) DEFAULT NULL COMMENT '操作人真实姓名（BR-SYS-07：关键动作同时记录账号与姓名）'
    AFTER username;
