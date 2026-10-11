-- =====================================================================
-- 升级脚本 V1.2：登录方式扩展（手机号/邮箱验证码、微信、员工自助注册）
-- 适用：已初始化的存量数据库（全新部署由 db/schema.sql 直接建表，无需执行本脚本）
-- 用法：docker compose exec -T mysql mysql -usmms -p"$SMMS_DB_PASSWORD" smms \
--         < db/upgrade-1.2-login-methods.sql
--
-- 背景：原登录仅支持「用户名 + 密码」，不符合门店员工实际使用习惯。
--       本脚本为其补充手机号 / 邮箱 / 微信三种登录凭证所需的存储结构。
--
-- ⚠ 执行前请先检查存量手机号是否有重复值，否则 uk_phone 唯一索引会创建失败：
--     SELECT phone, COUNT(*) c FROM sys_user
--     WHERE phone IS NOT NULL AND phone <> '' GROUP BY phone HAVING c > 1;
--   若有结果，请先人工合并/清空重复手机号再执行本脚本。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. sys_user 扩展第三方登录凭证字段
-- ---------------------------------------------------------------------
ALTER TABLE sys_user
    ADD COLUMN email           VARCHAR(100) DEFAULT NULL COMMENT '邮箱（邮箱验证码登录/注册）' AFTER phone,
    ADD COLUMN nickname        VARCHAR(64)  DEFAULT NULL COMMENT '昵称（第三方登录来源，如微信昵称）' AFTER real_name,
    ADD COLUMN avatar          VARCHAR(255) DEFAULT NULL COMMENT '头像URL（第三方登录来源）' AFTER nickname,
    ADD COLUMN wechat_openid   VARCHAR(64)  DEFAULT NULL COMMENT '微信 OpenID（同一公众号/应用内唯一）' AFTER avatar,
    ADD COLUMN wechat_unionid  VARCHAR(64)  DEFAULT NULL COMMENT '微信 UnionID（同一开放平台账号下唯一，可为空）';

-- 登录凭证唯一索引：MySQL 唯一索引允许多个 NULL，故未绑定的账号不受影响
ALTER TABLE sys_user
    ADD UNIQUE KEY uk_phone (phone),
    ADD UNIQUE KEY uk_email (email),
    ADD UNIQUE KEY uk_wechat_openid (wechat_openid),
    ADD KEY idx_wechat_unionid (wechat_unionid);

-- ---------------------------------------------------------------------
-- 2. 登录验证码表（sys_auth_code）
--    验证码不落明文，仅存 BCrypt 哈希；一次性消费（used=1 后不可复用）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_auth_code (
    id            BIGINT UNSIGNED AUTO_INCREMENT COMMENT '验证码ID',
    channel       VARCHAR(10)  NOT NULL COMMENT '发送渠道：SMS短信 / EMAIL邮件',
    target        VARCHAR(100) NOT NULL COMMENT '接收目标（手机号或邮箱）',
    code_hash     VARCHAR(100) NOT NULL COMMENT '验证码哈希（BCrypt），不存明文',
    scene         VARCHAR(20)  NOT NULL COMMENT '使用场景：LOGIN登录 / REGISTER注册 / BIND绑定',
    used          TINYINT      NOT NULL DEFAULT 0 COMMENT '是否已使用：1是 0否（一次性）',
    attempt_count INT          NOT NULL DEFAULT 0 COMMENT '已尝试校验次数（防暴力猜解）',
    request_ip    VARCHAR(45)  DEFAULT NULL COMMENT '发送请求来源IP',
    expire_time   DATETIME     NOT NULL COMMENT '过期时间',
    send_time     DATETIME     NOT NULL COMMENT '发送时间',
    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_ac_target (channel, target, create_time),
    KEY idx_ac_expire (expire_time)
) ENGINE = InnoDB COMMENT = '登录/注册验证码（不存明文，一次性消费）';

-- ---------------------------------------------------------------------
-- 3. 关于登录方式开关的说明（不写 sys_config）
-- ---------------------------------------------------------------------
-- smms.login.* 这组开关由 backend/src/main/resources/application.yaml 绑定到
-- LoginProperties，属于「启动期配置」，故意不放进 sys_config：
-- sys_config 是运行期热更新参数，两者混用会让管理员在「系统参数」页改了开关却毫无效果。
-- 如需调整登录方式，请改 application.yaml 的 smms.login 节后重启后端。
-- 需要运行期可调时，再改造成读 sys_config（参考 token_expire_hours 的写法）。
