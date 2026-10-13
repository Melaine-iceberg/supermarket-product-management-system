package com.smms.rpt.dto;

import java.math.BigDecimal;

/**
 * 库存周转率行（FR-RPT-04④）：周转率 = 销售成本 / 平均库存金额；
 * 周转天数 = 区间天数 / 周转率。期初 = 期末成本 − Σ(区间流水金额)。
 */
public record TurnoverRow(
        Long goodsId,
        String goodsCode,
        String goodsName,
        Long categoryId,
        String categoryName,
        BigDecimal stockQty,
        /** 期末库存成本金额（当前结存）；无权限时为空 */
        BigDecimal costAmount,
        /** 期初库存成本金额（区间开始前，由流水倒推）；无权限时为空 */
        BigDecimal beginAmount,
        /** 平均库存金额 = (期初 + 期末) / 2；无权限时为空 */
        BigDecimal avgAmount,
        /** 区间销售成本（扣除退货回冲）；无权限时为空 */
        BigDecimal saleCostAmount,
        BigDecimal returnCostAmount,
        BigDecimal flowAmount,
        /** 周转率（次数） */
        BigDecimal turnoverRate,
        /** 周转天数 */
        BigDecimal turnoverDays) {
}
