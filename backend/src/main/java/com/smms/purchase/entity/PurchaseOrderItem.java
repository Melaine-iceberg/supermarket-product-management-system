package com.smms.purchase.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

import java.math.BigDecimal;

/**
 * 采购单明细（DR-08）。商品删除校验（FR-GOODS-06）依赖本表统计，
 * 采购模块完整实现见后续步骤。
 */
@Data
@TableName("purchase_order_item")
public class PurchaseOrderItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long orderId;

    private Long goodsId;

    /** 订购数量 >0 */
    private BigDecimal quantity;

    /** 采购单价 ≥0 */
    private BigDecimal price;

    /** 行金额 = 数量×单价 */
    private BigDecimal amount;

    /** 已入库数量 */
    private BigDecimal receivedQty;

    /** 已退货数量 */
    private BigDecimal returnedQty;

    /** 生成草稿时的建议采购量（建议采购生成时记录，UC-INV-002 6a；手工建单为空） */
    private BigDecimal suggestedQty;

    /** 生成草稿时的建议单价（同上） */
    private BigDecimal suggestedPrice;

    private String remark;

    private String createBy;

    private LocalDateTime createTime;

    private String updateBy;

    private LocalDateTime updateTime;
}
