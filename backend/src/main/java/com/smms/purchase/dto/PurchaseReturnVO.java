package com.smms.purchase.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 采购退货单列表/详情（FR-PUR-08）：含原采购单、供应商、退货原因、
 * 审核信息与逐行退货数量、退货单价（入库时成本单价）、金额。
 */
public record PurchaseReturnVO(
        Long id,
        String orderNo,
        Long purchaseOrderId,
        String purchaseOrderNo,
        Long supplierId,
        String supplierName,
        String status,
        String returnReason,
        BigDecimal totalAmount,
        String createBy,
        LocalDateTime createTime,
        String auditBy,
        LocalDateTime auditTime,
        String auditRemark,
        String remark,
        List<ItemVO> items
) {

    public record ItemVO(
            Long id,
            Long purchaseItemId,
            Long goodsId,
            String goodsCode,
            String goodsName,
            String spec,
            String unitName,
            BigDecimal returnQty,
            BigDecimal returnPrice,
            BigDecimal amount,
            String remark
    ) {
    }
}
