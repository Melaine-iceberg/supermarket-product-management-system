package com.smms.sale.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

/**
 * 收银结算命令（FR-SALE-06）：事务内完成销售单落库、库存条件扣减、
 * 流水写入、收款记录、会员消费统计。
 * 收银员不可改价（FR-SALE-03），单价由系统按取价规则解析（BR-GOODS-04）；
 * 整单折扣需店长授权（FR-SALE-07）。
 */
public record CheckoutCommand(

        /** 收银台号（01~99） */
        @NotNull(message = "收银台号不能为空")
        @Min(value = 1, message = "收银台号须为 1~99")
        @Max(value = 99, message = "收银台号须为 1~99")
        Integer counterNo,

        /** 会员ID（散客为空，FR-SALE-05） */
        Long memberId,

        /** 店长整单折扣率（0.7~1，BR-SALE-10） */
        @DecimalMin(value = "0.0001", message = "整单折扣率不合法")
        @DecimalMax(value = "1", message = "整单折扣率不合法")
        BigDecimal orderDiscountRate,

        /** 店长授权人姓名（整单折扣时必填，FR-SALE-07） */
        String authorizerName,

        /** 备注 */
        String remark,

        /** 销售明细（同一商品不得重复成行，BR-SALE-01） */
        @NotEmpty(message = "销售明细不能为空")
        @Valid
        List<CheckoutItem> items,

        /** 收款记录（一单多方式组合，FR-SALE-06） */
        @NotEmpty(message = "收款信息不能为空")
        @Valid
        List<CheckoutPayment> payments
) {

    /** 单条销售明细录入 */
    public record CheckoutItem(

            @NotNull(message = "商品ID不能为空")
            Long goodsId,

            /** 数量：普通商品正整数；称重商品为净重（≤3 位小数，BR-SALE-01） */
            @NotNull(message = "数量不能为空")
            @DecimalMin(value = "0.001", message = "数量必须大于 0")
            BigDecimal quantity,

            /** 净重（称重商品必填，普通商品忽略） */
            BigDecimal netWeight,

            /** 行备注 */
            String remark
    ) {
    }

    /** 单笔收款 */
    public record CheckoutPayment(

            /** 收款方式：CASH现金/BANK银行卡/MOBILE移动支付 */
            @NotBlank(message = "收款方式不能为空")
            String payType,

            /** 收款金额 */
            @NotNull(message = "收款金额不能为空")
            @DecimalMin(value = "0.01", message = "收款金额必须大于 0")
            BigDecimal amount
    ) {
    }
}
