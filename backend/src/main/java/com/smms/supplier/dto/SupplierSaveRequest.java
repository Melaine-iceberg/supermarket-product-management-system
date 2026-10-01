package com.smms.supplier.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 供应商新增/编辑请求（FR-SUP-01）：编码由系统生成、不可修改（BR-SUP-03）；
 * 无敏感字段查看权限的角色将脱敏值（****XXXX）或空值原样提交时，
 * 服务端保留库中原值（BR-SUP-01）。
 */
@Data
public class SupplierSaveRequest {

    @NotBlank(message = "供应商名称不能为空")
    @Size(max = 100, message = "供应商名称不能超过100字")
    private String name;

    @Size(max = 30, message = "联系人不能超过30字")
    private String contactPerson;

    /** 联系电话：手机号或固话格式（BR-SUP-01，服务端格式校验） */
    @Size(max = 20, message = "联系电话不能超过20位")
    private String phone;

    @Size(max = 200, message = "地址不能超过200字")
    private String address;

    @Email(message = "邮箱格式不正确")
    @Size(max = 50, message = "邮箱不能超过50字")
    private String email;

    @Size(max = 100, message = "开户行不能超过100字")
    private String bankName;

    /** 银行账号：脱敏值（****XXXX）原样提交时保留原值 */
    @Size(max = 50, message = "银行账号不能超过50位")
    private String bankAccount;

    /** 纳税人识别号：无权限时提交空值保留原值（BR-SUP-01） */
    @Size(max = 30, message = "纳税人识别号不能超过30位")
    private String taxNo;

    /** 结算方式：CASH现结/MONTHLY月结/CREDIT账期（缺省现结） */
    @Pattern(regexp = "CASH|MONTHLY|CREDIT", message = "结算方式不合法")
    private String settleType;

    /** 账期天数：结算方式为 CREDIT 时必填 */
    @Min(value = 1, message = "账期天数至少为1天")
    @Max(value = 365, message = "账期天数不能超过365天")
    private Integer creditDays;

    /** 采购提前期（天），缺省 3 */
    @Min(value = 0, message = "采购提前期不能为负数")
    @Max(value = 365, message = "采购提前期不能超过365天")
    private Integer leadTimeDays;

    @Size(max = 500, message = "备注不能超过500字")
    private String remark;
}
