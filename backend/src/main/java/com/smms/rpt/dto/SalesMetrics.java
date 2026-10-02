package com.smms.rpt.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 统计口径统一计算器（BR-RPT-01，全系统唯一定义，报表与看板必须一致）：
 * <pre>
 * 销售额   = Σ明细金额（折扣前）
 * 优惠额   = Σ明细优惠金额
 * 净销售额 = 销售额 − 优惠额
 * 退货额   = Σ退货单退款金额
 * 净销售   = 净销售额 − 退货额
 * 销售成本 = Σ(明细成本单价×数量) − Σ(退货明细成本单价×退货数量)
 * 毛利额   = 净销售 − 销售成本
 * 毛利率   = 毛利额 / 净销售 × 100%（净销售为 0 时显示 "—"，不做除零）
 * 客单价   = 净销售额 / 销售单数
 * </pre>
 * 全程 BigDecimal 定点运算（BR-RPT-05），禁止浮点累加。
 */
public record SalesMetrics(
        long orderCount,
        BigDecimal salesAmount,
        BigDecimal discountAmount,
        BigDecimal netSalesAmount,
        BigDecimal refundAmount,
        BigDecimal returnCostAmount,
        BigDecimal costAmount) {

    public SalesMetrics {
        salesAmount = nvl(salesAmount);
        discountAmount = nvl(discountAmount);
        netSalesAmount = nvl(netSalesAmount);
        refundAmount = nvl(refundAmount);
        returnCostAmount = nvl(returnCostAmount);
        costAmount = nvl(costAmount);
    }

    /** 净销售 = 净销售额 − 退货额 */
    public BigDecimal netAmount() {
        return netSalesAmount.subtract(refundAmount);
    }

    /** 销售成本（扣除退货回冲） */
    public BigDecimal netCostAmount() {
        return costAmount.subtract(returnCostAmount);
    }

    /** 毛利额 = 净销售 − 销售成本 */
    public BigDecimal profitAmount() {
        return netAmount().subtract(netCostAmount());
    }

    /** 毛利率 = 毛利额 / 净销售 × 100%；净销售为 0 时返回 null（前端显示 "—"） */
    public BigDecimal profitRate() {
        BigDecimal net = netAmount();
        if (net.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return profitAmount().multiply(BigDecimal.valueOf(100))
                .divide(net, 2, RoundingMode.HALF_UP);
    }

    /** 客单价 = 净销售额 / 销售单数；单数为 0 时返回 null */
    public BigDecimal avgOrderAmount() {
        if (orderCount == 0) {
            return null;
        }
        return netSalesAmount.divide(BigDecimal.valueOf(orderCount), 2, RoundingMode.HALF_UP);
    }

    /** 按百分比计算占比，基数为 0 时返回 null */
    public static BigDecimal share(BigDecimal part, BigDecimal total) {
        if (total == null || total.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return nvl(part).multiply(BigDecimal.valueOf(100))
                .divide(total, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
