package com.smms.goods.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 调价历史（DR-26，FR-GOODS-10）：零售价/会员价/进货参考价
 * 每次变更的前值、后值、操作人、时间与原因，用于解释毛利波动。
 */
@Data
@TableName("price_history")
public class PriceHistory {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long goodsId;

    /** 价格类型：RETAIL零售 / MEMBER会员 / PURCHASE进货 */
    private String priceType;

    private BigDecimal oldValue;

    private BigDecimal newValue;

    /** 生效时间 */
    private LocalDateTime effectiveTime;

    /** 操作人 */
    private String operatorName;

    /** 调价原因 */
    private String reason;

    /** 操作时间 */
    private LocalDateTime createTime;
}
