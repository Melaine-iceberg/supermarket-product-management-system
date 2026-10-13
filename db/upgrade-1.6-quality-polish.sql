-- =====================================================================
-- 升级脚本 V1.6：批次六（低成本高价值项收尾）数据级修订
-- 适用：已初始化的存量数据库（全新部署由 db/schema.sql + db/init-data.sql 初始化，无需执行本脚本）
-- 用法（仓库根目录）：
--   docker compose exec -T mysql mysql -usmms -p"$SMMS_DB_PASSWORD" smms < db/upgrade-1.6-quality-polish.sql
-- 内容：
--   GAP-P2-24（FR-SYS-06）：修正参数生效方式标注不实——instant_effect=0 的参数不强制刷缓存，
--     最迟 60 秒（读取缓存周期）内生效，无需重启；同步列注释与 token_expire_hours 备注。
-- 说明：本批次无表/字段增删，仅元数据与备注修正；语句可重复执行（幂等）。
-- =====================================================================

SET NAMES utf8mb4;

-- ① 列注释与实际语义对齐（FR-SYS-06 / GAP-P2-24）
ALTER TABLE sys_config
    MODIFY COLUMN instant_effect TINYINT NOT NULL DEFAULT 1
    COMMENT '是否立即生效：1=保存后立即清参数缓存生效；0=不强制刷新缓存，最迟60秒（缓存周期）内生效（FR-SYS-06/GAP-P2-24）';

-- ② token_expire_hours 备注澄清生效范围（对新签发 Token 生效，存量 Token 不受影响）
UPDATE sys_config
SET remark = 'JWT有效期；修改后最迟 60 秒内对新签发 Token 生效，存量 Token 不受影响（无需重启，IR-SW-03）'
WHERE config_key = 'token_expire_hours';
