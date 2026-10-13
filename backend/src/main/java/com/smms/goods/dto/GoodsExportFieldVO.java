package com.smms.goods.dto;

/**
 * 导出字段项（FR-GOODS-12 / GAP-P2-07）：key 与后端导出白名单一致，label 为界面展示名。
 */
public record GoodsExportFieldVO(String key, String label) {
}
