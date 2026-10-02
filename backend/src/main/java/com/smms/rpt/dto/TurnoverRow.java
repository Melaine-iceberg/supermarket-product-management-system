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
        /** 期末库存成本金额（当前结存） */
        BigDecimal costAmount,
        /** 期初库存成本金额（区间开始前，由流水倒推） */
        BigDecimal beginAmount,
        /** 平均库存金额 = (期初 + 期末) / 2 */
        BigDecimal avgAmount,
        /** 区间销售成本（扣除退货回冲） */
        BigDecimal saleCostAmount,
        BigDecimal returnCostAmount,
        BigDecimal flowAmount,
        /** 周转率（次数） */
        BigDecimal turnoverRate,
        /** 周转天数 */
        BigDecimal turnoverDays) {

    public TurnoverRow {
        costAmount = costAmount == null ? BigDecimal.ZERO : costAmount;
        beginAmount = beginAmount == null ? BigDecimal.ZERO : beginAmount;
        avgAmount = avgAmount == null ? BigDecimal.ZERO : avgAmount;
        saleCostAmount = saleCostAmount == null ? BigDecimal.ZERO : saleCostAmount;
        returnCostAmount = returnCostAmount == null ? BigDecimal.ZERO : returnCostAmount;
        flowAmount = flowAmount == null ? BigDecimal.ZERO : flowAmount;
    }
}
