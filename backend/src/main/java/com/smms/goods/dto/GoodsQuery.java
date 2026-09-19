package com.smms.goods.dto;

/**
 * 商品列表查询条件（FR-GOODS-03）：名称/编码/条码模糊，分类含子树，
 * 品牌/状态/库存状态/是否称重/是否允许退货组合筛选；分页与排序。
 */
public record GoodsQuery(
        String name,
        String goodsCode,
        String barcode,
        Long categoryId,
        Long brandId,
        Integer status,
        /** 库存状态：NORMAL/WARNING/OUT/OVERSTOCK（FR-INV-05 口径） */
        String stockStatus,
        Integer isWeighed,
        Integer allowReturn,
        int page,
        int size,
        /** 排序字段白名单：goodsCode/name/quantity/retailPrice/createTime */
        String sortBy,
        /** 排序方向：asc/desc */
        String sortDir
) {
}
