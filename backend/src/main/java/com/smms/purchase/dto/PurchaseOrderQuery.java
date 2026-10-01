package com.smms.purchase.dto;

import java.time.LocalDate;

/**
 * 采购单列表查询（FR-PUR-03）：单号/供应商/状态/创建人/
 * 创建日期区间/预计到货日期区间/商品（名称或编码）筛选。
 */
public record PurchaseOrderQuery(
        String orderNo,
        Long supplierId,
        String status,
        String createBy,
        LocalDate createDateBegin,
        LocalDate createDateEnd,
        LocalDate expectDateBegin,
        LocalDate expectDateEnd,
        /** 商品筛选：匹配明细中商品名称或编码 */
        String goodsKeyword,
        int page,
        int size
) {
}
