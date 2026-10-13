package com.smms.sale.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 收银挂单（DR-21，FR-SALE-08）：暂存购物车明细供稍后取单结算，不扣库存、不计入报表（BR-RPT-03）；
 * 仅挂单收银员本人可见/可取/可删，超 24 小时由定时任务清理（IR-SW-06）。
 */
@Data
@TableName("hold_order")
public class HoldOrder {

    /** 状态：ACTIVE待取 / TAKEN已取 / DELETED已删除 */
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_TAKEN = "TAKEN";
    public static final String STATUS_DELETED = "DELETED";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 收银台号 */
    private Integer counterNo;

    /** 收银员ID（挂单归属，仅本人可见） */
    private Long cashierId;

    private String cashierName;

    /** 会员ID（挂单时已选） */
    private Long memberId;

    /** 会员姓名快照（取单展示） */
    private String memberName;

    /** 明细快照JSON（含商品展示字段与改价，取单后恢复购物车） */
    private String itemsJson;

    /** 件数摘要（称重行计 1） */
    private Integer itemCount;

    /** 金额摘要（改价行按改价单价） */
    private BigDecimal amount;

    /** 整单折扣率（挂单时已定，FR-SALE-07） */
    private BigDecimal orderRate;

    /** 整单折扣原因（FR-SALE-07） */
    private String discountReason;

    /** 整单折扣/改价授权账号（取单后重录密码） */
    private String authorizerUsername;

    private String remark;

    private LocalDateTime holdTime;

    private String status;

    /** 取单/删除时间 */
    private LocalDateTime takenTime;

    private String createBy;

    private LocalDateTime createTime;

    private String updateBy;

    private LocalDateTime updateTime;
}
