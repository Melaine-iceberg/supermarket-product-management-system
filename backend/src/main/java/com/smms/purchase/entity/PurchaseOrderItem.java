package com.smms.purchase.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

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

    private String remark;
}
