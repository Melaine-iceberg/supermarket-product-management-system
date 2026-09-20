package com.smms.member.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 会员等级（DR-23，FR-MEM-05）：折扣率参与收银取价与整单折扣计算（BR-SALE-10）。
 */
@Data
@TableName("member_level")
public class MemberLevel {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    /** 升级门槛（累计消费额） */
    private BigDecimal threshold;

    /** 折扣率（如 0.98 = 98 折） */
    private BigDecimal discountRate;

    /** 积分倍率 */
    private BigDecimal pointRate;

    private Integer sortNo;

    /** 状态：1启用 0停用 */
    private Integer status;

    private String createBy;

    private LocalDateTime createTime;

    private String updateBy;

    private LocalDateTime updateTime;
}
