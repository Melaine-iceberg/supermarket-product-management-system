package com.smms.sale.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * 销售退货命令（FR-SALE-10/11）：基于原销售单按原成交单价退款，
 * 事务内回补库存、按成本快照回冲、冲减会员消费额、更新原单退货状态；
 * 退款超阈值或原因为"误操作"时须店长授权（授权人 ≠ 发起人）。
 */
public record ReturnCreateRequest(

        @NotNull(message = "原销售单ID不能为空")
        Long salesOrderId,

        /** 退款方式：CASH/BANK/MOBILE（BALANCE 待会员模块开放） */
        @NotBlank(message = "退款方式不能为空")
        String refundType,

        /** 退货原因（字典 return_reason）：QUALITY/WRONG_ITEM/MISTAKE/OTHER */
        @NotBlank(message = "退货原因不能为空")
        String returnReason,

        /** 原因说明 */
        @Size(max = 200, message = "原因说明最多 200 字")
        String reasonRemark,

        /** 店长授权账号（超阈值/误操作/超期特批时填写，FR-SALE-11：店长账号+密码授权） */
        String authorizerUsername,

        /** 店长授权密码（与授权账号一并校验） */
        String authorizerPassword,

        @NotEmpty(message = "退货明细不能为空")
        @Valid
        List<ReturnItemRequest> items,

        /** 请求令牌（前端每次提交生成，NFR-REL-02 防重复提交；同令牌重放返回既有单据） */
        @Size(max = 64, message = "请求令牌长度不合法")
        String requestToken
) {

    public record ReturnItemRequest(

            @NotNull(message = "原销售明细ID不能为空")
            Long salesItemId,

            /** 退货数量（≤原数量−已退，BR-SALE-06） */
            @NotNull(message = "退货数量不能为空")
            @DecimalMin(value = "0.001", message = "退货数量必须大于 0")
            BigDecimal returnQty,

            @Size(max = 200, message = "备注最多 200 字")
            String remark
    ) {
    }
}
