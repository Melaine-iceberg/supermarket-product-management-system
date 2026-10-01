package com.smms.supplier.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 供应商业务统计（FR-SUP-02）：列表页一次聚合回填，避免逐行查询。
 */
@Data
public class SupplierStatVO {

    private Long supplierId;

    /** 在途采购单数（已提交/已审核未完成入库） */
    private Long inTransitCount;

    /** 未结算金额 = Σ(实收−退货−已结算)，仅已审核后状态且未结清单据 */
    private BigDecimal unsettledAmount;

    /** 最近合作时间（最近一次入库） */
    private LocalDateTime lastCoopTime;

    /** 供货商品数 */
    private Long goodsCount;
}
