package com.smms.sale.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * 挂单命令（FR-SALE-08）：暂存购物车明细快照供稍后取单，不扣库存、不计入报表（BR-RPT-03）。
 * 明细为展示快照（名称/规格/条码/单价等），实际取价与校验仍在结算时按 BR-GOODS-04 执行；
 * 整单折扣与改价在结算时须重新录入授权密码（挂单只保存授权账号）。
 * 件数与金额摘要由后端按明细计算（改价行按改价单价），防止前端篡改。
 */
public record HoldCreateCommand(

        /** 收银台号（01~99） */
        @NotNull(message = "收银台号不能为空")
        @Min(value = 1, message = "收银台号须为 1~99")
        @Max(value = 99, message = "收银台号须为 1~99")
        Integer counterNo,

        /** 会员ID（散客为空，FR-SALE-05） */
        Long memberId,

        /** 会员姓名快照（取单展示） */
        @Size(max = 30, message = "会员姓名过长")
        String memberName,

        /** 整单折扣率（挂单时已定，0~1，结算时仍校验下限制 BR-SALE-05） */
        @DecimalMin(value = "0.0001", message = "整单折扣率不合法")
        @DecimalMax(value = "1", message = "整单折扣率不合法")
        BigDecimal orderRate,

        /** 整单折扣原因（FR-SALE-07） */
        @Size(max = 200, message = "折扣原因不能超过 200 字符")
        String discountReason,

        /** 整单折扣/改价授权账号（取单后重录密码，安全考虑不落密码） */
        @Size(max = 30, message = "授权账号过长")
        String authorizerUsername,

        /** 备注 */
        @Size(max = 200, message = "备注不能超过 200 字符")
        String remark,

        /** 明细快照（前端购物车行） */
        @NotEmpty(message = "挂单明细不能为空")
        @Valid
        List<HoldItem> items
) {

    /** 挂单明细快照行（含无码商品与改价信息，取单后原样恢复购物车） */
    public record HoldItem(

            /** 行类型：NORMAL档案商品 / TEMP无码商品（FR-SALE-13） */
            @NotBlank(message = "行类型不能为空")
            String lineType,

            /** 商品ID（NORMAL 必填，TEMP 为空） */
            Long goodsId,

            @NotBlank(message = "商品名称不能为空")
            @Size(max = 100, message = "商品名称过长")
            String name,

            String spec,

            String barcode,

            @NotBlank(message = "单位不能为空")
            @Size(max = 10, message = "单位过长")
            String unitName,

            /** 零售价（TEMP 行为录入金额；金额摘要计算基准） */
            @NotNull(message = "单价不能为空")
            @DecimalMin(value = "0", message = "单价不能为负")
            BigDecimal retailPrice,

            /** 是否称重：1是 0否（称重行件数计 1） */
            @NotNull(message = "称重标识不能为空")
            Integer isWeighed,

            /** 数量（称重为净重，最多 3 位小数） */
            @NotNull(message = "数量不能为空")
            @DecimalMin(value = "0.001", message = "数量必须大于 0")
            BigDecimal quantity,

            String remark,

            /** 店长改价单价（仅 NORMAL，FR-SALE-07） */
            BigDecimal overridePrice,

            /** 改价原因（改价时必填） */
            @Size(max = 200, message = "改价原因不能超过 200 字符")
            String priceReason
    ) {
    }
}
