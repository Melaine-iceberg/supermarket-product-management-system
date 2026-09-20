package com.smms.member.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 会员列表/详情视图（FR-MEM-03）：含等级名称与业务统计
 * （累计消费额、消费次数、当前积分、储值余额、最近消费时间）。
 */
@Data
public class MemberListVO {

    private Long id;

    /** 会员编号：M+8位流水（BR-MEM-02） */
    private String memberNo;

    private String name;

    private String phone;

    /** 性别：1男 2女 */
    private Integer gender;

    private LocalDate birthday;

    private String address;

    private Long levelId;

    private String levelName;

    /** 当前积分 */
    private Integer points;

    /** 储值余额 */
    private BigDecimal balance;

    /** 累计消费额 = Σ实付-Σ退款（BR-MEM-06） */
    private BigDecimal totalConsume;

    /** 消费次数 */
    private Integer consumeTimes;

    private LocalDateTime lastConsumeTime;

    /** 注册人（收银员） */
    private String registerName;

    private LocalDateTime registerTime;

    /** 状态：1正常 0停用 */
    private Integer status;

    private String remark;
}
