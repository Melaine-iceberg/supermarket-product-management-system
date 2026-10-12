-- =====================================================================
-- 回退脚本：撤销 V1.2 登录方式扩展（多渠道登录 / 自助注册）
-- 对应升级脚本：db/upgrade-1.2-login-methods.sql
--
-- 适用：已执行过 upgrade-1.2-login-methods.sql 的存量数据库，需要把库结构
--       还原为 V1.1「用户名 + 密码」登录所需的形态。
--
-- 用法：docker compose exec -T mysql mysql -usmms -p"$SMMS_DB_PASSWORD" smms \
--         < db/rollback-1.2-login-methods.sql
--
-- ⚠ 执行前务必先备份数据库：本脚本会删除手机号 / 邮箱 / 微信绑定字段，
--    这些数据删除后不可恢复。
--    备份示例：docker compose exec -T mysql mysqldump -uroot -p"$SMMS_DB_ROOT_PASSWORD" smms \
--                > db/backup/smms-before-rollback-1.2.sql
--
-- 本脚本可重复执行（幂等）。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 丢弃登录/注册验证码表
--    该表只存短期验证码（一次性、最长 10 分钟有效），无业务价值，直接删除。
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS sys_auth_code;

-- ---------------------------------------------------------------------
-- 2. sys_user 去掉 V1.2 新增的登录凭证字段与唯一索引
--    以 email 列是否存在判断是否已升级，避免重复执行报错。
--    注意：会一并删除 phone/email/wechat_openid 的唯一约束。
-- ---------------------------------------------------------------------
SET @has_v12_column := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'sys_user'
      AND column_name = 'email'
);

SET @ddl := IF(@has_v12_column > 0,
    'ALTER TABLE sys_user
        DROP INDEX uk_phone,
        DROP INDEX uk_email,
        DROP INDEX uk_wechat_openid,
        DROP INDEX idx_wechat_unionid,
        DROP COLUMN email,
        DROP COLUMN nickname,
        DROP COLUMN avatar,
        DROP COLUMN wechat_openid,
        DROP COLUMN wechat_unionid',
    'DO 0');

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------
-- 3. 自助注册产生的账号（人工确认后执行，默认注释掉）
--    回退代码后这些账号既无法用邮箱/手机号登录，也只能用「用户名 + 密码」登录。
--    如需清理，请先确认哪些是 V1.2 自助注册的账号（email 非空且非管理员）：
--
--      SELECT id, username, real_name, phone, email, create_time
--      FROM sys_user WHERE email IS NOT NULL ORDER BY id;
--
--    确认后再执行删除（示例：删除 id 为 106、107 的账号）：
--
--      DELETE FROM sys_user_role WHERE user_id IN (106, 107);
--      DELETE FROM sys_user       WHERE id       IN (106, 107);
--
--    用户还可能留有登录日志 / 操作日志，按需一并清理：
--      DELETE FROM sys_login_log    WHERE user_id IN (106, 107);
--      DELETE FROM sys_operation_log WHERE user_id IN (106, 107);
-- ---------------------------------------------------------------------
