package com.smms.member.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 积分流水（DR-24，FR-MEM-07，BR-MEM-05）：只增不改不删。
 * 变动类型：CONSUME消费 / RETURN退货 / MANUAL人工调整（FR-MEM-09）。
 */
@Data
@TableName("point_record")
public class PointRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long memberId;

    /** 变动类型：CONSUME消费 / RETURN退货 / MANUAL人工调整 */
    private String changeType;

    /** 变动值（消费+，退货-） */
    private Integer changeValue;

    /** 变动后积分余额 */
    private Integer afterValue;

    /** 来源单据号（销售单/退货单） */
    private String bizNo;

    private String operatorName;

    private LocalDateTime createTime;

    /** 备注（人工调整原因 / 扣至0差额说明） */
    private String remark;
}
