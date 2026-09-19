package com.smms.goods.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品详情-关联供应商行（FR-GOODS-04）：来自供应商商品关联 join 供应商。
 */
@Data
public class SupplierBriefVO {

    private Long supplierId;

    private String supplierName;

    /** 供货单价 */
    private BigDecimal supplyPrice;

    /** 是否默认供应商：1是 0否 */
    private Integer isDefault;

    private LocalDateTime lastSupplyTime;
}
