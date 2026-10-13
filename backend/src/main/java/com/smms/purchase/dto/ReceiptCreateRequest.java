package com.smms.purchase.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 采购入库验收请求（FR-PUR-06）：录入实收数量与实收单价；
 * 超收或价差超容差须店长授权（BR-PUR-04/05），授权人记录写入单据。
 */
@Data
public class ReceiptCreateRequest {

    @NotNull(message = "采购单不能为空")
    private Long purchaseOrderId;

    /** 店长授权账号（超收/价差超容差时必填，BR-PUR-04/05：店长账号+密码授权） */
    private String authorizerUsername;

    /** 店长授权密码（与授权账号一并校验） */
    private String authorizerPassword;

    /** 供应商送货单号 */
    private String deliveryOrderNo;

    private String remark;

    @NotEmpty(message = "入库明细不能为空")
    @Valid
    private List<ItemRequest> items;

    /** 请求令牌（前端每次提交生成，NFR-REL-02 防重复提交；同令牌重放返回既有单据） */
    @Size(max = 64, message = "请求令牌长度不合法")
    private String requestToken;

    @Data
    public static class ItemRequest {

        @NotNull(message = "采购明细不能为空")
        private Long purchaseItemId;

        /** 实收数量 > 0 */
        @NotNull(message = "实收数量不能为空")
        @DecimalMin(value = "0.001", message = "实收数量必须大于 0")
        private BigDecimal receivedQty;

        /** 实收单价 ≥ 0（存货成本以此为准，BR-PUR-07；最多 4 位小数 DR-C-07） */
        @NotNull(message = "实收单价不能为空")
        @DecimalMin(value = "0", message = "实收单价不能为负")
        @jakarta.validation.constraints.Digits(integer = 10, fraction = 4,
                message = "实收单价最多 4 位小数（DR-C-07）")
        private BigDecimal receivedPrice;

        private String remark;
    }
}
