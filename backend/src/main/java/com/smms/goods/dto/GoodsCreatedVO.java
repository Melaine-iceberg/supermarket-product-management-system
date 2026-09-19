package com.smms.goods.dto;

/**
 * 商品新增结果（FR-GOODS-01/07）：回显系统生成的编码与店内码。
 */
public record GoodsCreatedVO(Long id, String goodsCode, String barcode) {
}
