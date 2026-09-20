package com.smms.member.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 会员等级规则保存请求（FR-MEM-05）：名称、升级门槛（累计消费额）、
 * 折扣率、积分倍率、排序、状态。
 */
@Data
public class LevelSaveRequest {

    @NotBlank(message = "等级名称不能为空")
    @Size(max = 30, message = "等级名称不能超过30字")
    private String name;

    /** 升级门槛：累计消费额（BR-MEM-06 判定依据） */
    @NotNull(message = "升级门槛不能为空")
    @DecimalMin(value = "0", message = "升级门槛不能为负数")
    private BigDecimal threshold;

    /** 折扣率：(0,1]，如 0.98=98折（BR-SALE-10） */
    @NotNull(message = "折扣率不能为空")
    @DecimalMin(value = "0.0001", message = "折扣率必须大于0")
    @DecimalMax(value = "1", message = "折扣率不能超过1")
    private BigDecimal discountRate;

    /** 积分倍率：(0,99]，如 1.2（BR-MEM-05） */
    @NotNull(message = "积分倍率不能为空")
    @DecimalMin(value = "0.0001", message = "积分倍率必须大于0")
    @DecimalMax(value = "99", message = "积分倍率过大")
    private BigDecimal pointRate;

    /** 排序（门槛升序展示） */
    @NotNull(message = "排序不能为空")
    @Min(value = 0, message = "排序不能为负数")
    private Integer sortNo;

    /** 状态：1启用 0停用 */
    @NotNull(message = "状态不能为空")
    @Min(value = 0, message = "状态不合法")
    @Max(value = 1, message = "状态不合法")
    private Integer status;
}
