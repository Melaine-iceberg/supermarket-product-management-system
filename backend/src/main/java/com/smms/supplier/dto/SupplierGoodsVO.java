package com.smms.supplier.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 供应商供货商品行（FR-SUP-04）：含商品编码/名称/规格/分类/单位。
 */
@Data
public class SupplierGoodsVO {

    private Long id;

    private Long goodsId;

    private String goodsCode;

    private String goodsName;

    private String spec;

    private String categoryName;

    private String unitName;

    /** 供货单价 */
    private BigDecimal supplyPrice;

    /** 是否默认供应商：1是 0否 */
    private Integer isDefault;

    /** 最近供货时间 */
    private LocalDateTime lastSupplyTime;

    /** 最近供货价（最近一次入库实收单价） */
    private BigDecimal lastSupplyPrice;

    /** 商品状态：1启用 0停用（采购选品仅可选启用商品） */
    private Integer goodsStatus;
}
