package com.smms.sale.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 收银班次（DR-37，FR-SALE-12）：收银员开班/交班登记，
 * 交班时统计销售单数、销售总额（净销售）、各收款方式金额、退货额与应收现金，
 * 生成交班单供打印与核对（FR-RPT-09）。
 */
@Data
@TableName("cashier_shift")
public class CashierShift {

    /** 班次状态：WORKING在班 / CLOSED已交班 */
    public static final String STATUS_WORKING = "WORKING";
    public static final String STATUS_CLOSED = "CLOSED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long cashierId;

    private String cashierName;

    /** 收银台号 */
    private Integer counterNo;

    /** 开班时间 */
    private LocalDateTime startTime;

    /** 交班时间 */
    private LocalDateTime endTime;

    /** 销售单数 */
    private Integer orderCount;

    /** 销售总额（净销售 = Σ应收 − 退货额） */
    private BigDecimal salesAmount;

    private BigDecimal cashAmount;

    private BigDecimal bankAmount;

    private BigDecimal mobileAmount;

    /** 储值收款额（储值开放前恒为 0） */
    private BigDecimal balanceAmount;

    /** 退货额（原单属于本班次的退货合计） */
    private BigDecimal returnAmount;

    /** 应收现金 = 现金收款 − 现金退款 */
    private BigDecimal expectedCash;

    private String status;

    private String createBy;

    private LocalDateTime createTime;

    private String updateBy;

    private LocalDateTime updateTime;
}
