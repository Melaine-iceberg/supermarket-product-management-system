-- =====================================================================
-- 升级脚本 V1.1：异步任务表（async_task）
-- 适用：已初始化的存量数据库（全新部署由 db/schema.sql 直接建表，无需执行本脚本）
-- 用法：docker compose exec -T mysql mysql -usmms -p"$SMMS_DB_PASSWORD" smms \
--         < db/upgrade-1.1-async-task.sql
-- 说明：重导出/导入提交改为异步执行，规避反向代理（Cloudflare 约 100s）524，见部署手册 §3.5
-- =====================================================================

CREATE TABLE IF NOT EXISTS async_task (
    id            BIGINT UNSIGNED AUTO_INCREMENT COMMENT '任务ID',
    task_no       CHAR(32)      NOT NULL COMMENT '任务编号（对外唯一标识）',
    task_type     VARCHAR(20)   NOT NULL COMMENT 'EXPORT导出/IMPORT导入',
    biz_type      VARCHAR(40)   NOT NULL COMMENT '业务类型：GOODS_EXPORT/SALES_REPORT_EXPORT/...',
    biz_name      VARCHAR(60)   NOT NULL COMMENT '任务名称（前端展示）',
    status        VARCHAR(10)   NOT NULL COMMENT 'QUEUED排队/RUNNING执行中/SUCCESS成功/FAILED失败',
    progress      TINYINT       NOT NULL DEFAULT 0 COMMENT '进度百分比 0~100',
    phase         VARCHAR(60)   DEFAULT NULL COMMENT '当前阶段描述',
    file_name     VARCHAR(120)  DEFAULT NULL COMMENT '结果文件名（下载时的默认名）',
    file_path     VARCHAR(255)  DEFAULT NULL COMMENT '结果文件绝对路径',
    file_size     BIGINT        DEFAULT NULL COMMENT '结果文件字节数',
    result_json   TEXT          DEFAULT NULL COMMENT '导入类任务的结果摘要（JSON）',
    error_message VARCHAR(500)  DEFAULT NULL COMMENT '失败原因',
    params_json   TEXT          DEFAULT NULL COMMENT '提交参数快照（JSON，便于排查与重试）',
    user_id       BIGINT UNSIGNED NOT NULL COMMENT '提交人ID',
    username      VARCHAR(30)   NOT NULL COMMENT '提交人账号',
    create_time   DATETIME      NOT NULL COMMENT '提交时间',
    start_time    DATETIME      DEFAULT NULL COMMENT '开始执行时间',
    finish_time   DATETIME      DEFAULT NULL COMMENT '结束时间',
    expires_at    DATETIME      NOT NULL COMMENT '过期时间（结果保留期，默认24小时）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_at_task_no (task_no),
    KEY idx_at_user (user_id, create_time),
    KEY idx_at_expires (expires_at),
    KEY idx_at_status (status)
) ENGINE = InnoDB COMMENT = '异步任务（导出/导入，只增不改状态字段）';
