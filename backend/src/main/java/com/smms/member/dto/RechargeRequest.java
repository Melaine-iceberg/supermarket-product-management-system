package com.smms.member.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 会员储值充值请求（FR-MEM-08）：登记金额与收款方式，须店长授权（授权人 ≠ 发起人）。
 * 授权校验方式与退货/改价一致（BR-SYS-06）：提交授权店长账号+密码，服务端核对身份、角色与在岗状态，
 * 落库真实授权人账号与姓名，杜绝"手填姓名"式伪造授权。
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

    /** 店长授权账号（须为店长及以上账号且 ≠ 发起人，FR-MEM-08） */
    @NotBlank(message = "储值充值须店长授权，请填写授权账号")
    private String authorizerUsername;

    /** 店长授权密码（与授权账号一并校验，BR-SYS-06） */
    @NotBlank(message = "储值充值须店长授权，请填写授权密码")
    private String authorizerPassword;

    @Size(max = 200, message = "备注不能超过200字")
    private String remark;
}
