-- =====================================================================
-- 升级脚本 V1.4：批次三（报表口径与审计一致性）数据结构增量
-- 适用：已初始化的存量数据库（全新部署由 db/schema.sql 直接建表，无需执行本脚本）
-- 用法：docker compose exec -T mysql mysql -usmms -p"$SMMS_DB_PASSWORD" smms \
--         < db/upgrade-1.4-report-audit.sql
-- 内容：
--   1) 采购单/入库单/采购退货单/盘点单/报损报溢单/库存流水补营业日字段
--      business_date（BR-RPT-02/GAP-P1-05）：报表按该字段聚合，不再运行时按
--      create_time/receipt_time/operate_time/audit_time 计算；各单据营业时刻映射：
--        采购单=下单时间、入库单=入库时间、采购退货单=创建时间、
--        库存流水=记账时间、盘点单/报损单=审核时间（审核前暂存创建/申请营业日，审核通过后改写）。
--   2) 按各单据营业时刻与系统参数 business_day_start 回填存量数据（默认 00:00）。
--   3) 补报表聚合索引 idx_*_biz_date（含销售退货单 return_order，其 business_date 列已存在）。
-- 注意：本脚本的 ALTER TABLE 仅在首次执行成功，重复执行会报 1060"Duplicate column"
--       并终止后续语句——请勿重复执行；若需重跑，请跳过对应的 ALTER 或加 --force。
-- =====================================================================

SET @bd_start = COALESCE((SELECT CAST(config_value AS TIME) FROM sys_config
                          WHERE config_key = 'business_day_start' LIMIT 1), CAST('00:00' AS TIME));

-- 1. 采购单：营业日 = 下单时刻归属
ALTER TABLE purchase_order
    ADD COLUMN business_date DATE NULL COMMENT '营业日（下单时刻+营业日起始时刻归属，BR-RPT-02/GAP-P1-05，报表按此聚合）' AFTER create_time;
UPDATE purchase_order
SET business_date = IF(TIME(create_time) < @bd_start, DATE(create_time) - INTERVAL 1 DAY, DATE(create_time));
ALTER TABLE purchase_order
    MODIFY COLUMN business_date DATE NOT NULL COMMENT '营业日（下单时刻+营业日起始时刻归属，BR-RPT-02/GAP-P1-05，报表按此聚合）',
    ADD INDEX idx_po_biz_date (business_date);

-- 2. 入库单：营业日 = 入库时刻归属
ALTER TABLE receipt_order
    ADD COLUMN business_date DATE NULL COMMENT '营业日（入库时刻+营业日起始时刻归属，BR-RPT-02/GAP-P1-05，到货及时率按此聚合）' AFTER receipt_time;
UPDATE receipt_order
SET business_date = IF(TIME(receipt_time) < @bd_start, DATE(receipt_time) - INTERVAL 1 DAY, DATE(receipt_time));
ALTER TABLE receipt_order
    MODIFY COLUMN business_date DATE NOT NULL COMMENT '营业日（入库时刻+营业日起始时刻归属，BR-RPT-02/GAP-P1-05，到货及时率按此聚合）',
    ADD INDEX idx_ro_biz_date (business_date);

-- 3. 采购退货单：营业日 = 创建时刻归属
ALTER TABLE purchase_return_order
    ADD COLUMN business_date DATE NULL COMMENT '营业日（创建时刻+营业日起始时刻归属，BR-RPT-02/GAP-P1-05）' AFTER create_time;
UPDATE purchase_return_order
SET business_date = IF(TIME(create_time) < @bd_start, DATE(create_time) - INTERVAL 1 DAY, DATE(create_time));
ALTER TABLE purchase_return_order
    MODIFY COLUMN business_date DATE NOT NULL COMMENT '营业日（创建时刻+营业日起始时刻归属，BR-RPT-02/GAP-P1-05）',
    ADD INDEX idx_pr_biz_date (business_date);

-- 4. 库存流水：营业日 = 记账时刻归属（库存变动/周转/期初报表按此聚合）
ALTER TABLE inventory_record
    ADD COLUMN business_date DATE NULL COMMENT '营业日（记账时刻+营业日起始时刻归属，BR-RPT-02/GAP-P1-05，变动/周转/期初报表按此聚合）' AFTER operate_time;
UPDATE inventory_record
SET business_date = IF(TIME(operate_time) < @bd_start, DATE(operate_time) - INTERVAL 1 DAY, DATE(operate_time));
ALTER TABLE inventory_record
    MODIFY COLUMN business_date DATE NOT NULL COMMENT '营业日（记账时刻+营业日起始时刻归属，BR-RPT-02/GAP-P1-05，变动/周转/期初报表按此聚合）',
    ADD INDEX idx_ir_biz_date (business_date);

-- 5. 盘点单：已审核按审核时刻归属，未审核暂存创建时刻归属（审核通过后由应用改写）
ALTER TABLE stock_check_order
    ADD COLUMN business_date DATE NULL COMMENT '营业日（创建时按创建时刻、审核通过后改写为审核时刻归属，BR-RPT-02/GAP-P1-05，盘亏报表按审核营业日聚合）' AFTER base_time;
UPDATE stock_check_order
SET business_date = IF(status = 'AUDITED' AND audit_time IS NOT NULL,
                       IF(TIME(audit_time) < @bd_start, DATE(audit_time) - INTERVAL 1 DAY, DATE(audit_time)),
                       IF(TIME(create_time) < @bd_start, DATE(create_time) - INTERVAL 1 DAY, DATE(create_time)));
ALTER TABLE stock_check_order
    MODIFY COLUMN business_date DATE NOT NULL COMMENT '营业日（创建时按创建时刻、审核通过后改写为审核时刻归属，BR-RPT-02/GAP-P1-05，盘亏报表按审核营业日聚合）',
    ADD INDEX idx_sc_biz_date (business_date);

-- 6. 报损报溢单：已审核按审核时刻归属，未审核暂存申请时刻归属（审核通过后由应用改写）
ALTER TABLE loss_order
    ADD COLUMN business_date DATE NULL COMMENT '营业日（创建时按申请时刻、审核通过后改写为审核时刻归属，BR-RPT-02/GAP-P1-05，损耗报表按审核营业日聚合）' AFTER audit_time;
UPDATE loss_order
SET business_date = IF(status = 'AUDITED' AND audit_time IS NOT NULL,
                       IF(TIME(audit_time) < @bd_start, DATE(audit_time) - INTERVAL 1 DAY, DATE(audit_time)),
                       IF(TIME(create_time) < @bd_start, DATE(create_time) - INTERVAL 1 DAY, DATE(create_time)));
ALTER TABLE loss_order
    MODIFY COLUMN business_date DATE NOT NULL COMMENT '营业日（创建时按申请时刻、审核通过后改写为审核时刻归属，BR-RPT-02/GAP-P1-05，损耗报表按审核营业日聚合）',
    ADD INDEX idx_lo_biz_date (business_date);

-- 7. 销售退货单补报表聚合索引（business_date 列 V1.0 已存在）
ALTER TABLE return_order
    ADD INDEX idx_rt_biz_date (business_date);
