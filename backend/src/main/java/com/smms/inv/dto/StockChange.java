package com.smms.inv.dto;

import java.math.BigDecimal;

/**
 * 库存变动命令（FR-INV-02）：所有库存增减的唯一入口参数。
 * qty 恒为正数，方向由 recordType 语义决定（入库 + / 出库 -，BR-INV-05）；
 * 流水由库存服务统一写入，保证前后结存与结存字段强一致（BR-INV-08）。
 */
public record StockChange(
        Long goodsId,
        /** 变动类型：PURCHASE_IN/SALE_RETURN/OVERFLOW_IN（入库）、SALE_OUT/PURCHASE_RETURN/LOSS_OUT（出库） */
        String recordType,
        /** 变动数量（恒正，最多 3 位小数，DR-C-07） */
        BigDecimal qty,
        /** 单价：成本重算类入库必填（移动加权基数）；销售退货为原销售明细成本快照；出库忽略（按当前成本结转） */
        BigDecimal unitCost,
        /** 关联单据类型：PO/RK/PT/XS/XT/PD/SZ/BY */
        String bizType,
        /** 关联单据号 */
        String bizNo,
        /** 操作人 */
        String operator,
        /** 备注 */
        String remark
) {
}
