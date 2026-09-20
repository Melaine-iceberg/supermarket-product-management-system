package com.smms.member.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 会员档案（DR-22）：本期销售模块只读（收银台会员识别与折扣，FR-SALE-05），
 * 会员管理功能见会员模块（MEM）步骤。
 */
@Data
@TableName("member")
public class Member {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 会员编号：M+8位流水 */
    private String memberNo;

    private String name;

    /** 手机号（全局唯一，主要识别键） */
    private String phone;

    /** 性别：1男 2女 */
    private Integer gender;

    private LocalDate birthday;

    private String address;

    private Long levelId;

    /** 当前积分 */
    private Integer points;

    /** 储值余额（不可为负） */
    private BigDecimal balance;

    /** 累计消费额 = Σ实付-Σ退款（等级判定依据，BR-MEM-06） */
    private BigDecimal totalConsume;

    /** 消费次数 */
    private Integer consumeTimes;

    private LocalDateTime lastConsumeTime;

    /** 注册人（收银员） */
    private String registerName;

    private LocalDateTime registerTime;

    /** 状态：1正常 0停用（BR-MEM-03：停用不享受会员价与折扣） */
    private Integer status;

    private String remark;

    private LocalDateTime updateTime;
}
