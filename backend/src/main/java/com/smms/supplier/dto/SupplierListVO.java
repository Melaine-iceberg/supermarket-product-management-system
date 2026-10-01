package com.smms.supplier.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 供应商列表/详情视图（FR-SUP-02）：含业务统计
 * （累计采购额、未结算金额、最近合作时间、在途采购单数、供货商品数）。
 * 银行账号按角色脱敏、纳税人识别号无权限时不返回（BR-SUP-01）。
 */
@Data
public class SupplierListVO {

    private Long id;

    /** 供应商编码：S+5位流水，创建后不可修改（BR-SUP-03） */
    private String supplierCode;

    private String name;

    private String contactPerson;

    private String phone;

    private String address;

    private String email;

    /** 开户行 */
    private String bankName;

    /** 银行账号：无权限时脱敏 ****XXXX（FR-SYS-09） */
    private String bankAccount;

    /** 纳税人识别号：无权限时不返回（null） */
    private String taxNo;

    /** 结算方式：CASH现结/MONTHLY月结/CREDIT账期 */
    private String settleType;

    /** 账期天数 */
    private Integer creditDays;

    /** 采购提前期（天） */
    private Integer leadTimeDays;

    /** 累计采购额（派生，入库验收时更新） */
    private BigDecimal totalPurchaseAmount;

    /** 未结算金额（FR-SUP-02） */
    private BigDecimal unsettledAmount;

    /** 最近合作时间（最近一次入库） */
    private LocalDateTime lastCoopTime;

    /** 在途采购单数（已提交/已审核未完成） */
    private Long inTransitCount;

    /** 供货商品数 */
    private Long goodsCount;

    /** 状态：1启用 0停用 */
    private Integer status;

    private String remark;

    private String createBy;

    private LocalDateTime createTime;

    private String updateBy;

    private LocalDateTime updateTime;
}
