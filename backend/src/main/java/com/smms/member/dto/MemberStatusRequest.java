package com.smms.member.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 会员停用/启用请求（FR-MEM-02，BR-MEM-03）：停用后不享受会员价与折扣。
 */
@Data
public class MemberStatusRequest {

    @NotNull(message = "状态不能为空")
    @Min(value = 0, message = "状态不合法")
    @Max(value = 1, message = "状态不合法")
    private Integer status;
}
