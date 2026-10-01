package com.smms.supplier.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 供应商档案（DR-05，FR-SUP-01）：
 * 编码 S+5位流水系统生成、创建后不可修改（BR-SUP-03）；名称唯一（BR-SUP-01）；
 * 银行账号与纳税人识别号仅店长及以上角色可见（BR-SUP-01）。
 */
@Data
@TableName("supplier")
public class Supplier {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 供应商编码：S+5位流水（BR-SUP-03） */
    private String supplierCode;

    /** 供应商名称（唯一，BR-SUP-01） */
    private String name;

    private String contactPerson;

    /** 联系电话：手机号或固话格式（BR-SUP-01） */
    private String phone;

    private String address;

    private String email;

    /** 开户行 */
    private String bankName;

    /** 银行账号（脱敏展示，FR-SYS-09） */
    private String bankAccount;

    /** 纳税人识别号 */
    private String taxNo;

    /** 结算方式（字典 settle_type）：CASH现结/MONTHLY月结/CREDIT账期 */
    private String settleType;

    /** 账期天数（结算方式为账期时必填） */
    private Integer creditDays;

    /** 采购提前期（天），参与建议采购量（BR-INV-07） */
    private Integer leadTimeDays;

    /** 累计采购额（派生数据，DR-C-04，入库验收时更新） */
    private BigDecimal totalPurchaseAmount;

    /** 状态：1启用 0停用（停用后不可被新采购单选择，FR-SUP-03） */
    private Integer status;

    private String remark;

    private String createBy;

    private LocalDateTime createTime;

    private String updateBy;

    private LocalDateTime updateTime;
}
