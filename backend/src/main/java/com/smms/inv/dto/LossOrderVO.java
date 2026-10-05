package com.smms.inv.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 报损/报溢单（FR-INV-10）：单商品单据，列表/详情共用；
 * reason 存字典代码，reasonLabel 由 sys_dict_item 关联展示（FR-SYS-07）。
 */
public record LossOrderVO(
        Long id,
        String orderNo,
        /** LOSS报损出库 / OVERFLOW报溢入库 */
        String bizType,
        Long goodsId,
        String goodsCode,
        String barcode,
        String goodsName,
        String categoryName,
        String unitName,
        String spec,
        Integer isWeighed,
        BigDecimal quantity,
        BigDecimal costPrice,
        BigDecimal amount,
        String reason,
        String reasonLabel,
        String applyName,
        /** PENDING待审核 / AUDITED已审核 / REJECTED已驳回 */
        String status,
        String auditorName,
        LocalDateTime auditTime,
        String auditRemark,
        String remark,
        LocalDateTime createTime) {
}
