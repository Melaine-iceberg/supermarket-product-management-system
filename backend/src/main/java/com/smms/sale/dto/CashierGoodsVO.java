package com.smms.sale.dto;

import java.math.BigDecimal;

/**
 * 收银台商品检索候选（FR-SALE-02）：
 * 条码/商品编码/PLU 精确命中优先，名称与拼音首字母模糊次之，仅含录入所需字段。
 */
public record CashierGoodsVO(
        Long id,
        String goodsCode,
        String barcode,
        String name,
        String spec,
        String pluCode,
        String unitName,
        BigDecimal retailPrice,
        Integer isWeighed) {
}
