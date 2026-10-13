package com.smms.purchase.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 采购单详情（FR-PUR-01/03）：单头 + 明细（含商品信息、已入库/已退货/待入库数量）。
 */
public record PurchaseOrderDetailVO(
        Long id,
        String orderNo,
        Long supplierId,
        String supplierName,
        String status,
        String createBy,
        LocalDateTime createTime,
        LocalDateTime submitTime,
        String auditBy,
        LocalDateTime auditTime,
        String auditRemark,
        LocalDate expectDate,
        BigDecimal totalAmount,
        BigDecimal receivedAmount,
        BigDecimal returnAmount,
        String settleStatus,
        BigDecimal settleAmount,
        String settleMethod,
        LocalDate settleDate,
        String settleBy,
        String terminateReason,
        Integer version,
        String remark,
        List<ItemVO> items
) {

    public record ItemVO(
            Long id,
            Long goodsId,
            String goodsCode,
            String goodsName,
            String spec,
            String unitName,
            /** 订购数量 */
            BigDecimal quantity,
            /** 采购单价 */
            BigDecimal price,
            /** 行金额 */
            BigDecimal amount,
            /** 已入库数量 */
            BigDecimal receivedQty,
            /** 已退货数量 */
            BigDecimal returnedQty,
            /** 待入库数量 = 订购 − 已入库（超收判断基数） */
            BigDecimal pendingQty,
            /** 建议采购量原值（建议采购生成时记录，UC-INV-002 6a；手工建单为空） */
            BigDecimal suggestedQty,
            /** 建议单价原值（同上） */
            BigDecimal suggestedPrice,
            String remark
    ) {
    }
}
