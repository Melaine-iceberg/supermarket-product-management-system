package com.smms.member.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

/**
 * 会员信息维护请求（FR-MEM-02）：可改姓名/手机号/性别/生日/地址/备注；
 * 积分、累计消费额、储值余额不可手工直接修改（不提供入参，由业务流水驱动）。
 */
@Data
public class MemberUpdateRequest {

    @NotBlank(message = "姓名不能为空")
    @Size(max = 30, message = "姓名不能超过30字")
    private String name;

    /** 手机号修改须校验唯一性（BR-MEM-01） */
    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号须为大陆 11 位手机号（BR-MEM-01）")
    private String phone;

    /** 性别：1男 2女 */
    private Integer gender;

    private LocalDate birthday;

    @Size(max = 200, message = "地址不能超过200字")
    private String address;

    @Size(max = 200, message = "备注不能超过200字")
    private String remark;
}
