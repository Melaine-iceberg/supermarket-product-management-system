package com.smms.purchase.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.smms.purchase.dto.PurchaseReturnQuery;
import com.smms.purchase.dto.PurchaseReturnVO;
import com.smms.purchase.entity.PurchaseReturnOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/** 采购退货单 Mapper（FR-PUR-08）。 */
@Mapper
public interface PurchaseReturnOrderMapper extends BaseMapper<PurchaseReturnOrder> {

    /** 当前最大退货单号（BR-PUR-01）：按前缀行锁取号 */
    String selectLastOrderNoForUpdate(@Param("prefix") String prefix);

    /** 退货单分页：退货单号/采购单号/供应商/状态/日期区间 */
    IPage<PurchaseReturnVO> selectReturnPage(IPage<PurchaseReturnVO> page,
                                             @Param("q") PurchaseReturnQuery q,
                                             @Param("beginTime") LocalDateTime beginTime,
                                             @Param("endTime") LocalDateTime endTime);
}
