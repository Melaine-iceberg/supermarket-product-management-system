package com.smms.member.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 会员储值充值请求（FR-MEM-08）：登记金额与收款方式，须店长授权（授权人 ≠ 发起人）。
 */
@Data
public class RechargeRequest {

    @NotNull(message = "充值金额不能为空")
    @DecimalMin(value = "0.01", message = "充值金额必须大于 0")
    private BigDecimal amount;

    /** 收款方式：CASH现金 / BANK银联 / MOBILE移动支付 */
    @NotBlank(message = "收款方式不能为空")
    @Pattern(regexp = "CASH|BANK|MOBILE", message = "收款方式不合法（CASH/BANK/MOBILE）")
    private String payType;

    /** 店长授权人姓名（须与发起人不同，FR-SALE-11 同款授权规则） */
    @NotBlank(message = "储值充值须店长授权，请填写授权人")
    private String authorizerName;

    @Size(max = 200, message = "备注不能超过200字")
    private String remark;
}
