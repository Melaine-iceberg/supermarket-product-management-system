package com.smms.purchase.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.smms.purchase.dto.ReceiptOrderVO;
import com.smms.purchase.dto.ReceiptQuery;
import com.smms.purchase.entity.ReceiptOrderItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 入库单明细 Mapper（DR-10）。 */
@Mapper
public interface ReceiptOrderItemMapper extends BaseMapper<ReceiptOrderItem> {

    /** 最近 3 次采购平均价（BR-PUR-05 黄色提示基准；无入库记录返回 null） */
    BigDecimal selectRecentAvgPrice(@Param("goodsId") Long goodsId);

    /** 采购明细入库时成本单价（FR-PUR-08：Σ(单价×数量)/Σ数量，无入库返回 null） */
    BigDecimal selectAvgReceiptPrice(@Param("purchaseItemId") Long purchaseItemId);

    /** 入库单分页（FR-PUR-07）：入库单号/采购单号/供应商/日期区间/操作人 */
    IPage<ReceiptOrderVO> selectReceiptPage(IPage<ReceiptOrderVO> page,
                                            @Param("q") ReceiptQuery q,
                                            @Param("beginTime") LocalDateTime beginTime,
                                            @Param("endTime") LocalDateTime endTime);
}
