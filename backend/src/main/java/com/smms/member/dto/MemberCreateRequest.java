package com.smms.member.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

/**
 * 会员注册请求（FR-MEM-01）：必填姓名与手机号；
 * 积分/储值/累计消费不在注册时录入（初始为 0）。
 */
@Data
public class MemberCreateRequest {

    @NotBlank(message = "姓名不能为空")
    @Size(max = 30, message = "姓名不能超过30字")
    private String name;

    /** 手机号：全局唯一、主要识别键（BR-MEM-01） */
    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号须为大陆 11 位手机号（BR-MEM-01）")
    private String phone;

    /** 性别：1男 2女（不采集证件信息，C-07） */
    private Integer gender;

    private LocalDate birthday;

    @Size(max = 200, message = "地址不能超过200字")
    private String address;

    @Size(max = 200, message = "备注不能超过200字")
    private String remark;
}
