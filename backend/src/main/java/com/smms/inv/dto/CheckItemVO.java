package com.smms.inv.dto;

import java.math.BigDecimal;

/**
 * 盘点明细行（FR-INV-08）：账面/实盘/差异 + 商品信息（扫码定位行用条码）。
 * 差异由服务端按 实盘-账面 计算，不信任客户端传入。
 */
public record CheckItemVO(
        Long id,
        Long goodsId,
        String goodsCode,
        String barcode,
        String name,
        Long categoryId,
        String categoryName,
        String unitName,
        String spec,
        Integer isWeighed,
        BigDecimal bookQty,
        BigDecimal actualQty,
        BigDecimal diffQty,
        BigDecimal costPrice,
        BigDecimal diffAmount,
        String diffReason,
        String remark) {
}
